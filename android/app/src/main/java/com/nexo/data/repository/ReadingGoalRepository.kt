package com.nexo.data.repository

import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.sync.GoalReconciliation
import com.nexo.data.sync.GoalSync
import com.nexo.data.sync.GoalValue
import com.nexo.data.sync.ReadingGoalRemoteDataSource
import kotlinx.coroutines.CancellationException

/**
 * Outcome of [ReadingGoalRepository.sync].
 *
 * @property minutes the reconciled goal, or `null` when neither side has one.
 * @property remoteReachable whether the remote fetch actually succeeded. `false`
 *   means the answer came from the local cache alone, so a `null` [minutes] is
 *   "unknown", not "this account is new".
 */
data class GoalSyncResult(
    val minutes: Int?,
    val remoteReachable: Boolean,
)

/**
 * The account's daily goal, coordinated between the local cache
 * ([ReadingGoalPreferences]) and the remote store (`user_preferences`).
 *
 * ## Local is a cache, remote is the shared truth
 *
 * The goal is one of the few values that must agree across every device the
 * account signs in on. The local copy still exists, but only to answer a cold
 * start with no network; the remote copy is what makes a second device see the
 * goal at all.
 *
 * ## Reconciliation is last-write-wins (T2)
 *
 * [sync] reconciles the two copies through [GoalSync], the pure rule. The
 * decision to document, not bury: when both copies exist and disagree, **the one
 * with the newer `updatedAt` wins** — there is no merge for a scalar preference.
 * Ties go to local (the value the user is looking at). See [GoalSync] for why.
 *
 * ## Offline
 *
 * Every remote call is wrapped: a failure is **not** treated as "no remote
 * goal". Collapsing those two answers is what would send an offline returning
 * user through onboarding again — the exact bug this repository removes. On a
 * remote failure [sync] simply leaves the local cache alone and reports [Result]
 * with the local value, and the next successful [sync] pushes it up.
 */
class ReadingGoalRepository(
    private val local: ReadingGoalPreferences,
    private val remote: ReadingGoalRemoteDataSource,
    private val activeUserIdProvider: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Reconcile local and remote for [userId], converging both sides on the
     * last-write-wins winner.
     *
     * Safe to call on every sign-in / cold start: it is idempotent, and when
     * both sides already agree it performs no write.
     *
     * @return the goal the caller should present, and whether the remote was
     *   actually reached. The reachability flag is essential: a `null` goal is a
     *   *definite* "new account" only when the remote answered
     *   ([GoalSyncResult.remoteReachable] `true`). When the remote could not be
     *   reached, `null` just means "no local cache yet" and the answer stays
     *   open — see [com.nexo.presentation.navigation.ReadingGoalGate].
     */
    suspend fun sync(userId: String?): GoalSyncResult {
        if (userId.isNullOrBlank()) {
            return GoalSyncResult(minutes = local.loadValue(userId)?.minutes, remoteReachable = false)
        }

        val remoteValue = runCatching { remote.fetchGoal(userId) }
        // A transport failure is not "no goal": treat the remote as unknown and
        // answer from the cache rather than pretending the account is new.
        if (remoteValue.isFailure) {
            return GoalSyncResult(minutes = local.loadValue(userId)?.minutes, remoteReachable = false)
        }

        val localValue = local.loadValue(userId)
        return when (val outcome = GoalSync.reconcile(local = localValue, remote = remoteValue.getOrNull())) {
            is GoalReconciliation.Absent ->
                GoalSyncResult(minutes = null, remoteReachable = true)

            is GoalReconciliation.Keep ->
                GoalSyncResult(minutes = outcome.value.minutes, remoteReachable = true)

            is GoalReconciliation.PushLocal -> {
                // Local is authoritative; make the remote match it. A failed push
                // is not fatal — the local cache is already correct and the next
                // sync retries.
                push(userId, outcome.value)
                GoalSyncResult(minutes = outcome.value.minutes, remoteReachable = true)
            }

            is GoalReconciliation.AdoptRemote -> {
                // Remote is newer: hydrate the cache with the REMOTE clock, not
                // `now()`, so the value keeps its true age for the next LWW
                // comparison instead of being re-stamped as fresh.
                local.save(outcome.value.minutes, outcome.value.updatedAt, userId)
                GoalSyncResult(minutes = outcome.value.minutes, remoteReachable = true)
            }
        }
    }

    /**
     * Save the user's chosen goal: local cache first (so the UI and any offline
     * continuation are correct immediately), then the remote. A remote failure is
     * **not** surfaced as a lost write — the local cache already holds the value
     * and the next [sync] (local wins by its fresh clock) pushes it up.
     *
     * @param userId the owner; resolved from the active session when omitted.
     *   With no owner at all (signed out / anonymous, decision D5) nothing is
     *   written and the value is reported back unchanged.
     */
    suspend fun save(
        minutes: Int,
        userId: String? = activeUserIdProvider(),
    ): Result<Int> {
        if (userId.isNullOrBlank()) return Result.success(minutes)
        val stamp = now()
        local.save(minutes, stamp, userId)
        push(userId, GoalValue(minutes, stamp))
        return Result.success(minutes)
    }

    private suspend fun push(
        userId: String,
        value: GoalValue,
    ): Result<Unit> =
        try {
            remote.saveGoal(userId, value.minutes, value.updatedAt)
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // Offline / transient: the local cache is the truth until the next
            // sync. Reported so callers can log it — see the class doc.
            Result.failure(error)
        }
}
