package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/** [parseNotificationPreferences] tolerance: valid maps parse, garbage falls back to defaults. */
class NotificationPreferencesParseTest {
    @Test
    fun `valid map parses all fields`() {
        val raw: Map<String, Any?> =
            mapOf(
                "system" to false,
                "nudge" to false,
                "quietHours" to mapOf("enabled" to true, "start" to "01:30", "end" to "05:45"),
            )
        val parsed = parseNotificationPreferences(raw)
        assertEquals(
            NotificationPreferences(
                system = false,
                nudge = false,
                quietHours = QuietHoursSchedule(enabled = true, start = "01:30", end = "05:45"),
            ),
            parsed,
        )
    }

    @Test
    fun `null map returns defaults`() {
        assertEquals(NotificationPreferences.DEFAULT, parseNotificationPreferences(null))
    }

    @Test
    fun `garbage values fall back to defaults`() {
        val raw: Map<String, Any?> =
            mapOf(
                "system" to "yes",
                "nudge" to 3,
                "quietHours" to "not-a-map",
            )
        assertEquals(NotificationPreferences.DEFAULT, parseNotificationPreferences(raw))
    }

    @Test
    fun `partial map fills the missing fields with defaults`() {
        val raw: Map<String, Any?> = mapOf("nudge" to false)
        assertEquals(
            NotificationPreferences(
                system = NotificationPreferences.DEFAULT.system,
                nudge = false,
                quietHours = NotificationPreferences.DEFAULT.quietHours,
            ),
            parseNotificationPreferences(raw),
        )
    }

    @Test
    fun `empty strings inside quiet hours fall back to defaults`() {
        val raw: Map<String, Any?> =
            mapOf(
                "quietHours" to mapOf("enabled" to true, "start" to "", "end" to ""),
            )
        val parsed = parseNotificationPreferences(raw)
        assertEquals("22:00", parsed.quietHours.start)
        assertEquals("07:00", parsed.quietHours.end)
        assertEquals(true, parsed.quietHours.enabled)
    }
}
