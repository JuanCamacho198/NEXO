package com.nexo.data.remote.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexo.data.remote.supabase.SupabaseBookCatalogSync
import com.nexo.data.remote.supabase.SupabaseProgressSync
import com.nexo.data.remote.sync.SyncService
import com.nexo.debug.DebugLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot drain worker (S6).
 *
 * This worker is a SCHEDULER, not a drain implementation: it only re-invokes the
 * existing sync entry points and returns immediately. `startProcessing()` is
 * non-suspending and already launches the in-process, session-gated drain loop,
 * so all drain semantics stay untouched here (LWW/tie-break, tombstone guard,
 * FK guards, coalescing, poison-3, gated backoff, settle gate). The worker
 * deliberately never calls `Result.retry()`: WorkManager backoff would duplicate
 * the in-process gated backoff and could double-drain.
 *
 * The Drive push ([SyncService.schedulePush]) runs FIRST and synchronously: a
 * BOOK outbox row enqueued by an import is only consumed by the Drive push (the
 * push is what sets `remotePath`). The catalog drain ([SupabaseBookCatalogSync])
 * defers any book whose `remotePath` is still blank, so running the catalog
 * without first giving the Drive push a chance leaves the outbox row parked
 * forever — the book never reaches Drive and stays invisible to other devices.
 */
@HiltWorker
class OutboxDrainWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val progressSync: SupabaseProgressSync,
        private val catalogSync: SupabaseBookCatalogSync,
        private val driveSync: SyncService,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            // Drive push first: it is the only consumer that sets `remotePath`.
            // A disabled/unauthenticated Drive (or a push failure) must not stop
            // the catalog/progress drains, so this is non-fatal and logged.
            //
            // Both the success and the failure outcome are logged (contract:
            // "when one fails, the failure is logged"). A silent no-op here is
            // exactly how an imported book stayed parked in the outbox for four
            // days, so `schedulePush()`'s own result is recorded too, not just a
            // thrown exception.
            val pushOutcome = runCatching { driveSync.schedulePush() }
            pushOutcome
                .onSuccess { result ->
                    if (result.isFailure) {
                        DebugLog.warn(
                            TAG,
                            "Drive schedulePush declined: ${result.exceptionOrNull()?.message}",
                        )
                    } else {
                        DebugLog.info(TAG, "Drive schedulePush completed before the catalog drain")
                    }
                }.onFailure { DebugLog.warn(TAG, "Drive schedulePush threw: ${it.message}") }
            progressSync.startProcessing()
            catalogSync.startProcessing()
            return Result.success()
        }

        private companion object {
            const val TAG = "OutboxDrainWorker"
        }
    }
