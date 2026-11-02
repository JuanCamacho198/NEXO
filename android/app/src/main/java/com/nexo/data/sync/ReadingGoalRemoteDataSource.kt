package com.nexo.data.sync

/**
 * Remote seam for the account's daily goal (`public.user_preferences`).
 *
 * Kept as an interface so [com.nexo.data.repository.ReadingGoalRepository] can be
 * driven from unit tests with a fake that fails on demand (the offline case),
 * and so the Supabase dependency stays out of the repository's tests entirely.
 */
interface ReadingGoalRemoteDataSource {
    /**
     * The remote goal for [userId], or `null` when the account has no row.
     *
     * Implementations must let transport failures propagate: the caller treats
     * any thrown error as "offline", not as "no goal" — the two answers must not
     * be collapsed, or a network blip would look like a fresh account and send
     * the user through onboarding again.
     */
    suspend fun fetchGoal(userId: String): GoalValue?

    /**
     * Write [minutes] for [userId] with [updatedAt] as the LWW clock, upserting
     * on the `user_id` primary key.
     */
    suspend fun saveGoal(
        userId: String,
        minutes: Int,
        updatedAt: Long,
    )
}
