package com.nexo.data.session

import android.content.Context
import com.nexo.domain.session.ActiveUserProvider

/**
 * Persistent daily reading goal (REQ-daily-reading-goal-1), scoped to the
 * active account (T10/T11 of `aislamiento-datos-por-cuenta`).
 *
 * Stored in `nexo_reading_goal__{userId}`. Before this, the goal lived in one
 * global file, so a second account on the same device never saw onboarding
 * (the goal already "existed") and inherited the first account's target.
 *
 * - `load()` returns `null` when the goal is unset, invalid, or when no account
 *   is active → the onboarding goal step must be shown.
 * - `save(minutes)` persists the caller's goal; a no-op with no active account
 *   (decision D5: the anonymous path writes nothing).
 */
class ReadingGoalPreferences(
    context: Context,
    private val activeUser: ActiveUserProvider,
) {
    private val appContext = context.applicationContext

    /** @return the stored goal in minutes, or `null` when absent/invalid/no owner. */
    fun load(): Int? = load(activeUser.currentUserId())

    /**
     * The goal for an explicit [userId], bypassing the ambient owner.
     *
     * Used at the moment of sign-in, where the account is known from the session
     * the auth state just published but [ActiveUserProvider] may not have been
     * updated yet (see `authenticatedDestination`).
     */
    fun load(userId: String?): Int? {
        if (userId.isNullOrBlank() || ActiveUserProvider.isLocalUserId(userId)) return null
        val prefs =
            appContext.getSharedPreferences(
                AccountPreferences.fileNameForOwner(PREFS_NAME, userId) ?: return null,
                Context.MODE_PRIVATE,
            )
        val raw = prefs.getInt(KEY_DAILY_GOAL_MINUTES, -1)
        return raw.takeIf { it > 0 }
    }

    fun save(minutes: Int) {
        val prefs = AccountPreferences.open(appContext, PREFS_NAME, activeUser) ?: return
        prefs.edit().putInt(KEY_DAILY_GOAL_MINUTES, minutes).apply()
    }

    companion object {
        /** Base name; [AccountPreferences] appends the owner segment. */
        const val PREFS_NAME = "nexo_reading_goal"
        private const val KEY_DAILY_GOAL_MINUTES = "daily_goal_minutes"
    }
}
