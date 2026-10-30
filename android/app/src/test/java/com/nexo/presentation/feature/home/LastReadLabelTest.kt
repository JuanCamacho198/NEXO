package com.nexo.presentation.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastReadLabelTest {
    private val now = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun labelAgo(elapsed: Long) = LastReadLabel.from(now - elapsed, now)

    @Test
    fun `null timestamp renders no label`() {
        assertNull(LastReadLabel.from(null, now))
    }

    @Test
    fun `under one hour is just now`() {
        assertEquals(LastReadLabel.JustNow, labelAgo(0))
        assertEquals(LastReadLabel.JustNow, labelAgo(minute))
        assertEquals(LastReadLabel.JustNow, labelAgo(hour - 1))
    }

    @Test
    fun `exactly one hour is one hour ago`() {
        assertEquals(LastReadLabel.HoursAgo(1), labelAgo(hour))
    }

    @Test
    fun `twenty three hours is twenty three hours ago`() {
        assertEquals(LastReadLabel.HoursAgo(23), labelAgo(23 * hour))
    }

    @Test
    fun `exactly twenty four hours is yesterday`() {
        assertEquals(LastReadLabel.Yesterday, labelAgo(day))
    }

    @Test
    fun `exactly forty seven hours is yesterday`() {
        assertEquals(LastReadLabel.Yesterday, labelAgo(47 * hour))
    }

    @Test
    fun `exactly forty eight hours is two days ago`() {
        assertEquals(LastReadLabel.DaysAgo(2), labelAgo(2 * day))
    }

    @Test
    fun `several days collapse into day buckets`() {
        assertEquals(LastReadLabel.DaysAgo(5), labelAgo(5 * day))
        assertEquals(LastReadLabel.DaysAgo(30), labelAgo(30 * day + 5 * hour))
    }

    @Test
    fun `future timestamp from clock skew is just now`() {
        assertEquals(LastReadLabel.JustNow, LastReadLabel.from(now + day, now))
        assertEquals(LastReadLabel.JustNow, LastReadLabel.from(now + 5 * minute, now))
    }

    @Test
    fun `string res and format arg match each bucket`() {
        assertEquals(com.nexo.R.string.home_last_read_just_now, LastReadLabel.JustNow.stringRes())
        assertNull(LastReadLabel.JustNow.formatArg())

        assertEquals(com.nexo.R.string.home_last_read_hours_ago, LastReadLabel.HoursAgo(3).stringRes())
        assertEquals(3, LastReadLabel.HoursAgo(3).formatArg())

        assertEquals(com.nexo.R.string.home_last_read_yesterday, LastReadLabel.Yesterday.stringRes())
        assertNull(LastReadLabel.Yesterday.formatArg())

        assertEquals(com.nexo.R.string.home_last_read_days_ago, LastReadLabel.DaysAgo(4).stringRes())
        assertEquals(4, LastReadLabel.DaysAgo(4).formatArg())
    }
}
