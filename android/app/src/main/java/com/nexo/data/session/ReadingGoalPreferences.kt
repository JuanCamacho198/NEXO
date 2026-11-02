package com.nexo.data.session

import android.content.Context
import com.nexo.data.sync.GoalValue
import com.nexo.domain.session.ActiveUserProvider

/**
 * Persistent daily reading goal (REQ-daily-reading-goal-1), scoped to the
 * active account (T10/T11 of `aislamiento-datos-por-cuenta`).
 *
 * Stored in `nexo_reading_goal__{userId}`. Before this, the goal lived in one
 * global file, so a second account on the same device never saw onboarding
 * (the goal already "existed") and inherited the first account's target.
 *
 * ## Role after cross-device sync
 *
 * This class is the **local cache** of the account's goal, not its owner. The
 * goal now lives in `public.user_preferences` and travels between devices;
 * [com.nexo.data.repository.ReadingGoalRepository] writes through to both. The
 * cache exists so a cold start with no network still knows the goal without
 * waiting on a round trip.
 *
 * Because it is a cache, it also stores the **LWW clock** ([GoalValue.updatedAt])
 * alongside the minutes: reconciliation needs to know *when* the cached value
 * was written to decide whether it beats the remote copy. A goal cached without
 * a timestamp could not participate in last-write-wins.
 *
 * - [load] returns `null` when the goal is unset, invalid, or when no account is
 *   active → the onboarding goal step must be shown.
 * - [save] persists the caller's goal with `now` as the clock, or an explicit
 *   [save] timestamp when hydrating a remote value (so hydration does not
 *   re-stamp the remote value as newer than it is).
 * - Both are no-ops with no active account (decision D5: the anonymous path
 *   writes nothing).
 */
class ReadingGoalPreferences(
    context: Context,
    private val activeUser: ActiveUserProvider,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    /** @return the stored goal in minutes, or `null` when absent/invalid/no owner. */
    fun load(): Int? = load(activeUser.currentUserId())

    /**
     * The full cached goal — minutes **and** its LWW clock — for an explicit
     * [userId], or `null` when absent/invalid/no owner.
     *
     * The clock is required by reconciliation, so this is the reader the sync
     * layer uses; [load] remains the "does this account have a goal?" reader the
     * onboarding gate and the UI already depend on.
     */
    fun loadValue(userId: String? = activeUser.currentUserId()): GoalValue? {
        val prefs = openFor(userId) ?: return null
        val minutes = prefs.getInt(KEY_DAILY_GOAL_MINUTES, -1)
        if (minutes <= 0) return null
        // A goal written before this class stored a clock has no timestamp; `0`
        // makes it the oldest possible write, so any real remote value wins and
        // the value is still usable offline.
        return GoalValue(minutes = minutes, updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L))
    }

    /**
     * The goal for an explicit [userId], bypassing the ambient owner.
     *
     * Used at the moment of sign-in, where the account is known from the session
     * the auth state just published but [ActiveUserProvider] may not have been
     * updated yet (see `authenticatedDestination`).
     */
    fun load(userId: String?): Int? = loadValue(userId)?.minutes

    fun save(minutes: Int) {
        save(minutes, now())
    }

    /**
     * Persist [minutes] with an explicit LWW clock.
     *
     * Hydration from the remote passes the **remote** timestamp, not `now()`:
     * re-stamping would make a stale remote value look like a fresh local edit
     * and let it beat a genuinely newer local one on the next reconcile.
     *
     * @param userId the owner to write for; defaults to the active account.
     */
    fun save(
        minutes: Int,
        updatedAt: Long,
        userId: String? = activeUser.currentUserId(),
    ) {
        val prefs = openFor(userId) ?: return
        prefs
            .edit()
            .putInt(KEY_DAILY_GOAL_MINUTES, minutes)
            .putLong(KEY_UPDATED_AT, updatedAt)
            .apply()
    }

    private fun openFor(userId: String?) =
        AccountPreferences
            .fileNameForOwner(PREFS_NAME, userId)
            ?.let { appContext.getSharedPreferences(it, Context.MODE_PRIVATE) }

    companion object {
        /** Base name; [AccountPreferences] appends the owner segment. */
        const val PREFS_NAME = "nexo_reading_goal"
        private const val KEY_DAILY_GOAL_MINUTES = "daily_goal_minutes"
        private const val KEY_UPDATED_AT = "updated_at"
    }
}
