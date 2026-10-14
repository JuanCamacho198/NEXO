package com.nexo.data.session

import android.content.Context
import com.nexo.domain.notification.NotificationPreferences
import com.nexo.domain.notification.isQuietHoursActive
import com.nexo.domain.notification.parseNotificationPreferences
import org.json.JSONObject

/**
 * Persisted notification preferences (U9).
 *
 * Follows the hand-rolled SharedPreferences pattern used by
 * [ReadingGoalPreferences] / [AppThemePreferences] — this project has no
 * DataStore. The value is stored as a small JSON object and parsed through the
 * domain's [parseNotificationPreferences], so the store and the pure policy
 * share ONE shape: an unknown or corrupted blob degrades to
 * [NotificationPreferences.DEFAULT] instead of throwing.
 */
class NotificationPreferencesStore(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** @return the stored preferences, or defaults when absent/corrupted. */
    fun load(): NotificationPreferences {
        val raw = prefs.getString(KEY_PREFERENCES, null) ?: return NotificationPreferences.DEFAULT
        val parsed =
            runCatching { jsonToMap(JSONObject(raw)) }.getOrNull()
        return parseNotificationPreferences(parsed)
    }

    /** Persists [preferences] as a JSON blob (flat keys + nested quiet hours). */
    fun save(preferences: NotificationPreferences) {
        val json =
            JSONObject().apply {
                put("system", preferences.system)
                put("nudge", preferences.nudge)
                put(
                    "quietHours",
                    JSONObject().apply {
                        put("enabled", preferences.quietHours.enabled)
                        put("start", preferences.quietHours.start)
                        put("end", preferences.quietHours.end)
                    },
                )
            }
        prefs.edit().putString(KEY_PREFERENCES, json.toString()).apply()
    }

    /** True when quiet hours are enabled and [nowMs] falls inside the schedule. */
    fun isQuietHoursActive(nowMs: Long): Boolean = isQuietHoursActive(load(), nowMs)

    private fun jsonToMap(json: JSONObject): Map<String, Any?> =
        json.keys().asSequence().associateWith { key ->
            when (val value = json.get(key)) {
                is JSONObject -> jsonToMap(value)
                JSONObject.NULL -> null
                else -> value
            }
        }

    companion object {
        private const val PREFS_NAME = "nexo_notification_preferences"
        private const val KEY_PREFERENCES = "preferences"
    }
}
