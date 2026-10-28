package com.nexo.data.session

import android.content.Context
import com.nexo.domain.notification.NotificationPreferences
import com.nexo.domain.notification.isQuietHoursActive
import com.nexo.domain.notification.parseNotificationPreferences
import com.nexo.domain.session.ActiveUserProvider
import org.json.JSONObject

/**
 * Persisted notification preferences (U9), scoped to the active account
 * (T10 of `aislamiento-datos-por-cuenta`).
 *
 * Stored in `nexo_notification_preferences__{userId}`. The value is a small JSON
 * object parsed through the domain's [parseNotificationPreferences], so the store
 * and the pure policy share ONE shape: an unknown or corrupted blob degrades to
 * [NotificationPreferences.DEFAULT] instead of throwing.
 *
 * [load] returns defaults when no account is active; [save] is then a no-op
 * (decision D5: the anonymous path writes nothing).
 */
class NotificationPreferencesStore(
    context: Context,
    private val activeUser: ActiveUserProvider,
) {
    private val appContext = context.applicationContext

    /** @return the stored preferences, or defaults when absent/corrupted/no owner. */
    fun load(): NotificationPreferences {
        val prefs = AccountPreferences.open(appContext, PREFS_NAME, activeUser)
            ?: return NotificationPreferences.DEFAULT
        val raw = prefs.getString(KEY_PREFERENCES, null) ?: return NotificationPreferences.DEFAULT
        val parsed =
            runCatching { jsonToMap(JSONObject(raw)) }.getOrNull()
        return parseNotificationPreferences(parsed)
    }

    /** Persists [preferences] as a JSON blob (flat keys + nested quiet hours). */
    fun save(preferences: NotificationPreferences) {
        val prefs = AccountPreferences.open(appContext, PREFS_NAME, activeUser) ?: return
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
        /** Base name; [AccountPreferences] appends the owner segment. */
        const val PREFS_NAME = "nexo_notification_preferences"
        private const val KEY_PREFERENCES = "preferences"
    }
}
