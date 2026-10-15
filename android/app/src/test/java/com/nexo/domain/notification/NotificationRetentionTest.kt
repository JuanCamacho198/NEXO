package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure retention bounds (NOTIF-02): 200 count, 30 days, newest survive. */
class NotificationRetentionTest {
    private val now = 1_800_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    private fun notification(
        id: String,
        createdAt: Long,
    ) = Notification(
        id = id,
        createdAt = createdAt,
        source = NotificationSource.SYNC,
        category = Category.SYSTEM,
        severity = Severity.INFO,
        interruption = Interruption.SILENT,
        i18nKey = "notifications.event.sync.success",
    )

    @Test
    fun `keeps every entry inside the age window`() {
        val items =
            listOf(
                notification("recent", now - dayMs),
                notification("edge", now - NotificationRetention.MAX_AGE_MILLIS),
            )
        assertEquals(listOf("edge", "recent"), NotificationRetention.apply(items, now).map { it.id })
    }

    @Test
    fun `drops entries older than 30 days`() {
        val justExpired = now - NotificationRetention.MAX_AGE_MILLIS - 1
        val items =
            listOf(
                notification("expired", justExpired),
                notification("fresh", now),
            )
        assertEquals(listOf("fresh"), NotificationRetention.apply(items, now).map { it.id })
    }

    @Test
    fun `caps the count at 200 keeping the newest`() {
        val items = (1..205).map { notification("n$it", now - (205 - it) * 1000L) }
        val kept = NotificationRetention.apply(items, now)
        assertEquals(NotificationRetention.MAX_COUNT, kept.size)
        assertEquals("n6", kept.first().id)
        assertEquals("n205", kept.last().id)
    }

    @Test
    fun `returns chronological order regardless of input order`() {
        val items =
            listOf(
                notification("c", now - 1000),
                notification("a", now - 3000),
                notification("b", now - 2000),
            )
        assertEquals(listOf("a", "b", "c"), NotificationRetention.apply(items, now).map { it.id })
    }

    @Test
    fun `age constant is exactly 30 days in millis`() {
        assertEquals(30L * dayMs, NotificationRetention.MAX_AGE_MILLIS)
        assertTrue(NotificationRetention.MAX_COUNT == 200)
    }
}
