package com.nexo.data.sync

/**
 * The daily goal as it exists at one place and time.
 *
 * @param minutes the goal in minutes; always > 0 for a real preference.
 * @param updatedAt the moment this value was last written (epoch millis). This
 *   is the LWW clock: the value with the newer stamp wins, and the clock travels
 *   with the value across devices through the remote `updated_at` column.
 */
data class GoalValue(
    val minutes: Int,
    val updatedAt: Long,
)

/**
 * Result of reconciling the local and remote copies of the daily goal.
 *
 * A sealed type instead of a nullable pair so the caller cannot forget that
 * "nothing to do" and "adopt the remote/local value" are different outcomes:
 * the repository only persists and only pushes after inspecting this.
 */
sealed interface GoalReconciliation {
    /** Both sides agree (or only one exists). [value] is the winner to keep. */
    data class Keep(
        val value: GoalValue,
    ) : GoalReconciliation

    /** Local copy wins and must be pushed to the remote. */
    data class PushLocal(
        val value: GoalValue,
    ) : GoalReconciliation

    /** Remote copy wins and must be hydrated into the local cache. */
    data class AdoptRemote(
        val value: GoalValue,
    ) : GoalReconciliation

    /** Neither side has a goal (or neither is valid). Nothing is written. */
    data object Absent : GoalReconciliation
}

/**
 * Pure reconciliation of the local and remote daily goal (T2, LWW by
 * `updatedAt`).
 *
 * ## The conflict this resolves
 *
 * Device A changes the goal offline and does not sync. Device B changes it too
 * and its write reaches the server. When A comes back online both copies exist
 * and disagree. There is no merge that is correct for a scalar preference — a
 * goal is one number, not a set — so the rule is **last write wins, by the
 * clock carried with each value**.
 *
 * Ties (identical [GoalValue.updatedAt]) break toward **local**: the two writes
 * happened at the same instant, and skewing toward the device the user is
 * holding keeps the value they just chose on screen.
 *
 * Invalid values (`minutes <= 0`) are treated as absent, matching
 * [com.nexo.data.session.ReadingGoalPreferences.load] which drops them: a
 * zero/negative goal is corruption, not a preference.
 *
 * The function is deliberately free of Android, Supabase and coroutines so the
 * whole rule is unit-testable without a device or a network.
 */
object GoalSync {
    fun reconcile(
        local: GoalValue?,
        remote: GoalValue?,
    ): GoalReconciliation {
        val localValid = local?.takeIf { it.minutes > 0 }
        val remoteValid = remote?.takeIf { it.minutes > 0 }

        if (localValid == null && remoteValid == null) return GoalReconciliation.Absent
        // The remote is the only copy (fresh install / wiped data): hydrate it.
        if (localValid == null) return GoalReconciliation.AdoptRemote(remoteValid!!)
        // The local is the only copy (first ever sync): push it up.
        if (remoteValid == null) return GoalReconciliation.PushLocal(localValid)

        return when {
            // Identical value and clock: there is nothing to converge. Without
            // this branch an equal-stamp reconcile would re-push the local value
            // on every sync forever, since ties below break toward local.
            localValid == remoteValid -> GoalReconciliation.Keep(localValid)
            localValid.updatedAt >= remoteValid.updatedAt -> GoalReconciliation.PushLocal(localValid)
            else -> GoalReconciliation.AdoptRemote(remoteValid)
        }
    }
}
