package com.nexo.data.session

import android.content.Context

/**
 * Persistent daily reading goal (REQ-daily-reading-goal-1).
 *
 * Follows the [AppThemePreferences] SharedPreferences pattern:
 * - `load()` returns `null` when the goal is unset or invalid → the onboarding
 *   goal step must be shown.
 * - `save(minutes)` persists the user-chosen goal.
 */
class ReadingGoalPreferences(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** @return the stored goal in minutes, or `null` when absent/invalid. */
    fun load(): Int? {
        val raw = prefs.getInt(KEY_DAILY_GOAL_MINUTES, -1)
        return raw.takeIf { it > 0 }
    }

    fun save(minutes: Int) {
        prefs.edit().putInt(KEY_DAILY_GOAL_MINUTES, minutes).apply()
    }

    companion object {
        private const val PREFS_NAME = "nextpage_reading_goal"
        private const val KEY_DAILY_GOAL_MINUTES = "daily_goal_minutes"
    }
}
