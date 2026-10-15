package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** [isSameDay] across adjacent days and the midnight boundary. */
class IsSameDayTest {
    private fun localMillis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Long =
        Calendar
            .getInstance()
            .apply {
                clear()
                set(year, month, day, hour, minute, second)
            }.timeInMillis

    @Test
    fun `same calendar day is true`() {
        val morning = localMillis(2024, Calendar.JANUARY, 10, 8, 0, 0)
        val evening = localMillis(2024, Calendar.JANUARY, 10, 23, 0, 0)
        assertTrue(isSameDay(morning, evening))
    }

    @Test
    fun `adjacent day is false`() {
        val day = localMillis(2024, Calendar.JANUARY, 10, 12, 0, 0)
        val nextDay = localMillis(2024, Calendar.JANUARY, 11, 12, 0, 0)
        assertFalse(isSameDay(day, nextDay))
    }

    @Test
    fun `one second apart across midnight is false`() {
        val beforeMidnight = localMillis(2024, Calendar.JANUARY, 10, 23, 59, 59)
        val afterMidnight = localMillis(2024, Calendar.JANUARY, 11, 0, 0, 0)
        assertFalse(isSameDay(beforeMidnight, afterMidnight))
    }

    @Test
    fun `identical timestamps are the same day`() {
        val moment = localMillis(2024, Calendar.JANUARY, 10, 0, 0, 0)
        assertTrue(isSameDay(moment, moment))
        assertEquals(true, isSameDay(moment, moment))
    }
}
