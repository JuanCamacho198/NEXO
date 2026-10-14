package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/** U6: pure relative-timestamp bucketing for the tray rows. */
class NotificationTimestampTest {
    private val now = 1_800_000_000_000L

    private fun bucket(createdAt: Long) = NotificationTimestamp.relative(createdAt, now)

    @Test
    fun `within a minute is just now`() {
        assertEquals(NotificationTimestamp.Bucket.JUST_NOW, bucket(now - 30_000).bucket)
    }

    @Test
    fun `minutes bucket counts whole minutes`() {
        val relative = bucket(now - 5 * 60_000L)
        assertEquals(NotificationTimestamp.Bucket.MINUTES, relative.bucket)
        assertEquals(5L, relative.amount)
    }

    @Test
    fun `hours bucket counts whole hours`() {
        val relative = bucket(now - 3 * 60 * 60_000L)
        assertEquals(NotificationTimestamp.Bucket.HOURS, relative.bucket)
        assertEquals(3L, relative.amount)
    }

    @Test
    fun `days bucket counts whole days`() {
        val relative = bucket(now - 2 * 24 * 60 * 60_000L)
        assertEquals(NotificationTimestamp.Bucket.DAYS, relative.bucket)
        assertEquals(2L, relative.amount)
    }

    @Test
    fun `a future timestamp clamps to just now`() {
        assertEquals(NotificationTimestamp.Bucket.JUST_NOW, bucket(now + 60_000).bucket)
    }
}
