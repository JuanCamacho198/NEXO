package com.nexo.data.remote.sync

import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Encapsulates the outbox-process loop duplicated across
 * [com.nexo.data.remote.supabase.SupabaseProgressSync] and
 * [com.nexo.data.remote.supabase.SupabaseBookCatalogSync].
 *
 * Single-item contract (`commit`):
 * - [ApplyOutcome.Ok]      → `outboxDao.deleteById(item.id)` + [CommitOutcome.Acked]
 * - [ApplyOutcome.Retryable] → `outboxDao.incrementRetryCount(item.id, error)` +
 *                              [CommitOutcome.Retryable]. The CALLER decides whether
 *                              to gate (e.g. progress syncer enters gated backoff
 *                              for 401s, catalog syncer retries immediately).
 * - [ApplyOutcome.Poison] OR retryCount >= [maxRetries] AFTER increment →
 *                            `outboxDao.pruneFailedItems(maxRetries)` +
 *                            [CommitOutcome.Poison].
 *
 * Threshold check is AFTER the increment: the 3rd failure (retryCount was 2
 * before, becomes 3 after increment, >= maxRetries=3) poisons.
 *
 * NO backoff policy is enforced here — the helper returns typed outcomes and
 * leaves delay/scheduling decisions to the per-domain caller.
 *
 * `processOutboxStream` pulls pending items via the provided flow, invokes
 * [commit] for each, and emits one [CommitEvent] per item in order. Items that
 * produce [CommitOutcome.Retryable] are still emitted as [CommitEvent.Retried]
 * so consumers can update progress; [CommitOutcome.Poison] is emitted as
 * [CommitEvent.Pruned].
 */
class OutboxCommit(
    private val outboxDao: SyncOutboxDao,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {
    /**
     * Run a single outbox item. The caller's [apply] returns an [ApplyOutcome]
     * describing the remote write. The helper persists the result and returns
     * the typed [CommitOutcome] for the caller to act on (e.g. gate on retry).
     */
    suspend fun commit(
        item: SyncOutboxEntity,
        apply: suspend () -> ApplyOutcome,
    ): CommitOutcome =
        when (val outcome = apply()) {
            is ApplyOutcome.Ok -> {
                outboxDao.deleteById(item.id)
                CommitOutcome.Acked
            }

            is ApplyOutcome.Retryable -> {
                val error = outcome.cause.message ?: outcome.cause::class.simpleName.orEmpty()
                outboxDao.incrementRetryCount(item.id, error)
                // Threshold check is AFTER the increment — the 3rd failure poisons.
                if (item.retryCount + 1 >= maxRetries) {
                    outboxDao.pruneFailedItems(maxRetries)
                    CommitOutcome.Poison(outcome.cause)
                } else {
                    CommitOutcome.Retryable(outcome.cause)
                }
            }

            is ApplyOutcome.Poison -> {
                // Immediate poison: caller has decided the item is unrecoverable.
                outboxDao.pruneFailedItems(maxRetries)
                CommitOutcome.Poison(outcome.cause)
            }
        }

    /**
     * Stream helper: pulls pending items via [items], invokes [commit] for
     * each, and emits a [CommitEvent] per item in the order produced.
     *
     * Ordering is preserved (sequential processing).
     */
    fun processOutboxStream(
        items: Flow<List<SyncOutboxEntity>>,
        apply: suspend (SyncOutboxEntity) -> ApplyOutcome,
    ): Flow<CommitEvent> =
        flow {
            items.collect { batch ->
                for (item in batch) {
                    when (val outcome = commit(item) { apply(item) }) {
                        is CommitOutcome.Acked ->
                            emit(CommitEvent.Acked(itemId = item.id))

                        is CommitOutcome.Retryable -> {
                            val error =
                                outcome.cause.message
                                    ?: outcome.cause::class.simpleName.orEmpty()
                            emit(
                                CommitEvent.Retried(
                                    itemId = item.id,
                                    retryCount = item.retryCount + 1,
                                    error = error,
                                ),
                            )
                        }

                        is CommitOutcome.Poison -> {
                            val error =
                                outcome.cause.message
                                    ?: outcome.cause::class.simpleName.orEmpty()
                            emit(CommitEvent.Pruned(itemId = item.id, error = error))
                        }
                    }
                }
            }
        }

    companion object {
        const val DEFAULT_MAX_RETRIES: Int = 3
    }
}

/** Outcome the per-domain caller reports after attempting a remote write. */
sealed interface ApplyOutcome {
    data object Ok : ApplyOutcome

    data class Retryable(
        val cause: Throwable,
    ) : ApplyOutcome

    data class Poison(
        val cause: Throwable,
    ) : ApplyOutcome
}

/** Outcome [OutboxCommit] returns to the caller (already persisted in DAO). */
sealed interface CommitOutcome {
    data object Acked : CommitOutcome

    data class Retryable(
        val cause: Throwable,
    ) : CommitOutcome

    data class Poison(
        val cause: Throwable,
    ) : CommitOutcome
}

/** Per-item event emitted by [OutboxCommit.processOutboxStream]. */
sealed interface CommitEvent {
    data class Acked(
        val itemId: String,
    ) : CommitEvent

    data class Retried(
        val itemId: String,
        val retryCount: Int,
        val error: String,
    ) : CommitEvent

    data class Pruned(
        val itemId: String,
        val error: String,
    ) : CommitEvent
}
