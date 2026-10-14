package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** [isQuietHoursActive] across wrapping, non-wrapping, degenerate and malformed ranges. */
class QuietHoursTest {
    /** Epoch millis for a LOCAL wall-clock time on a fixed reference day. */
    private fun localMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis

    private fun prefs(
        enabled: Boolean,
        start: String,
        end: String,
    ) = NotificationPreferences(system = true, nudge = true, QuietHoursSchedule(enabled, start, end))

    @Test
    fun `overnight wrap is active at 23 00`() {
        assertTrue(isQuietHoursActive(prefs(true, "22:00", "07:00"), localMillis(2024, Calendar.JANUARY, 10, 23, 0)))
    }

    @Test
    fun `overnight wrap is active at 02 00`() {
        assertTrue(isQuietHoursActive(prefs(true, "22:00", "07:00"), localMillis(2024, Calendar.JANUARY, 10, 2, 0)))
    }

    @Test
    fun `overnight wrap is inactive at 12 00`() {
        assertFalse(isQuietHoursActive(prefs(true, "22:00", "07:00"), localMillis(2024, Calendar.JANUARY, 10, 12, 0)))
    }

    @Test
    fun `overnight wrap boundary start is active and end is inactive`() {
        assertEquals(true, isQuietHoursActive(prefs(true, "22:00", "07:00"), localMillis(2024, Calendar.JANUARY, 10, 22, 0)))
        assertEquals(false, isQuietHoursActive(prefs(true, "22:00", "07:00"), localMillis(2024, Calendar.JANUARY, 10, 7, 0)))
    }

    @Test
    fun `non-wrapping range is active inside and inactive outside`() {
        assertTrue(isQuietHoursActive(prefs(true, "09:00", "17:00"), localMillis(2024, Calendar.JANUARY, 10, 12, 0)))
        assertFalse(isQuietHoursActive(prefs(true, "09:00", "17:00"), localMillis(2024, Calendar.JANUARY, 10, 8, 0)))
    }

    @Test
    fun `non-wrapping range end boundary is exclusive`() {
        assertFalse(isQuietHoursActive(prefs(true, "09:00", "17:00"), localMillis(2024, Calendar.JANUARY, 10, 17, 0)))
    }

    @Test
    fun `degenerate range never silences`() {
        assertFalse(isQuietHoursActive(prefs(true, "09:00", "09:00"), localMillis(2024, Calendar.JANUARY, 10, 9, 0)))
    }

    @Test
    fun `malformed times never silence`() {
        val when9am = localMillis(2024, Calendar.JANUARY, 10, 9, 0)
        assertFalse(isQuietHoursActive(prefs(true, "9", "17:00"), when9am))
        assertFalse(isQuietHoursActive(prefs(true, "09:00", "25:00"), when9am))
        assertFalse(isQuietHoursActive(prefs(true, "09:60", "17:00"), when9am))
        assertFalse(isQuietHoursActive(prefs(true, "abc", "17:00"), when9am))
    }

    @Test
    fun `disabled schedule never silences`() {
        assertFalse(isQuietHoursActive(prefs(false, "22:00", "07:00"), localMillis(2024, Calendar.JANUARY, 10, 23, 0)))
    }
}
