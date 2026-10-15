package com.nexo.domain.notification

import java.util.Calendar

/** Quiet hours schedule; times are local `HH:MM` (24h). */
data class QuietHoursSchedule(
    val enabled: Boolean,
    val start: String,
    val end: String,
)

/** Per-category switches plus quiet hours. */
data class NotificationPreferences(
    val system: Boolean,
    val nudge: Boolean,
    val quietHours: QuietHoursSchedule,
) {
    companion object {
        val DEFAULT =
            NotificationPreferences(
                system = true,
                nudge = true,
                quietHours = QuietHoursSchedule(enabled = false, start = "22:00", end = "07:00"),
            )
    }
}

/** `HH:MM` -> minutes since local midnight, or null when malformed. */
private const val MAX_HOUR = 23
private const val MAX_MINUTE = 59
private const val MINUTES_PER_HOUR = 60

private fun parseClockTime(value: String): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value.trim()) ?: return null
    val hours = match.groupValues[1].toInt()
    val minutes = match.groupValues[2].toInt()
    if (hours > MAX_HOUR || minutes > MAX_MINUTE) return null
    return hours * MINUTES_PER_HOUR + minutes
}

/**
 * True when quiet hours are enabled and [nowMs] falls inside the schedule.
 *
 * Overnight ranges ([start] > [end], e.g. 22:00-07:00) wrap past midnight. A
 * degenerate range (`start == end`) or malformed input never silences.
 */
fun isQuietHoursActive(
    prefs: NotificationPreferences,
    nowMs: Long,
): Boolean {
    val schedule = prefs.quietHours
    if (!schedule.enabled) return false
    val start = parseClockTime(schedule.start)
    val end = parseClockTime(schedule.end)
    if (start == null || end == null || start == end) return false
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = nowMs
    val current = calendar.get(Calendar.HOUR_OF_DAY) * MINUTES_PER_HOUR + calendar.get(Calendar.MINUTE)
    return if (start < end) {
        current >= start && current < end
    } else {
        // Overnight wrap: the range runs through local midnight.
        current >= start || current < end
    }
}

/** Accepts anything persisted and returns a valid shape (unknown -> defaults). */
fun parseNotificationPreferences(raw: Map<String, Any?>?): NotificationPreferences {
    val defaults = NotificationPreferences.DEFAULT
    if (raw == null) return defaults
    val quiet = (raw["quietHours"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()

    fun boolAt(
        map: Map<*, *>,
        key: String,
        fallback: Boolean,
    ): Boolean = (map[key] as? Boolean) ?: fallback

    fun stringAt(
        map: Map<*, *>,
        key: String,
        fallback: String,
    ): String {
        val value = map[key] as? String
        return if (value != null && value.isNotEmpty()) value else fallback
    }

    return NotificationPreferences(
        system = boolAt(raw, "system", defaults.system),
        nudge = boolAt(raw, "nudge", defaults.nudge),
        quietHours =
            QuietHoursSchedule(
                enabled = boolAt(quiet, "enabled", defaults.quietHours.enabled),
                start = stringAt(quiet, "start", defaults.quietHours.start),
                end = stringAt(quiet, "end", defaults.quietHours.end),
            ),
    )
}
