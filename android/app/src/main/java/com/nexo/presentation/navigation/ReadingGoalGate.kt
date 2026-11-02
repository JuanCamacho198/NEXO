package com.nexo.presentation.navigation

import com.nexo.data.repository.ReadingGoalRepository

/**
 * Resolves "does this account have a daily goal?" **across devices** (T3).
 *
 * ## Why a local-only answer is not enough
 *
 * [OnboardingGate] reads the local cache, which is correct on the device that
 * set the goal. On a second device — same account, freshly installed, cache
 * empty — the local answer is "no goal", and `resolveStartDestination` would send
 * the user to the onboarding step. A moment later the remote value arrives and
 * the screen is wrong in retrospect. That is the flash this type removes.
 *
 * ## The rule
 *
 * The account's goal question has three answers, not two, and they are the same
 * three [resolveStartDestination] already understands:
 *
 * - `true` — a goal exists (locally, or hydrated from the remote);
 * - `false` — the remote was reached and confirms there is no goal: a genuinely
 *   new account, which is the only case that should see onboarding;
 * - `null` — not resolved yet. Either the account id has not landed, or the
 *   remote is unreachable and the cache is empty so it cannot answer. `null`
 *   keeps the host on its spinner rather than guessing, exactly like the
 *   unresolved-account case.
 *
 * ## Offline
 *
 * When [ReadingGoalRepository.sync] cannot reach the remote it answers from the
 * cache: a non-null goal there resolves `true` (the user keeps reading, no
 * onboarding), and a null cache leaves the question unresolved (`null`) rather
 * than claiming `false`. A returning user is never mistaken for a new one.
 */
object ReadingGoalGate {
    /**
     * Runs the cross-device resolution for [userId].
     *
     * @return `true`/`false`/`null` per the rule above. Never throws: a failure
     *   in sync is reported by the repository as the cache answer, and an
     *   unexpected throw is mapped to `null` (unresolved) so a transient fault
     *   cannot route a returning user through onboarding.
     */
    suspend fun resolve(
        repository: ReadingGoalRepository,
        userId: String?,
    ): Boolean? {
        val outcome = runCatching { repository.sync(userId) }.getOrNull() ?: return null
        return when {
            outcome.minutes != null -> true
            // No goal anywhere: only a *confirmed* remote absence is a definite
            // "new account". If the remote was not reached, the question stays
            // open instead of being answered "false".
            outcome.remoteReachable -> false
            else -> null
        }
    }
}
