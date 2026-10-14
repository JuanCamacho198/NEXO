package com.nexo.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/** U6: pure tray grouping (mirrors the desktop rule). */
class NotificationGroupingTest {
    private fun notification(
        id: String,
        createdAt: Long,
        source: NotificationSource = NotificationSource.IMPORT,
        i18nKey: String = "notifications.event.import.success",
    ) = Notification(
        id = id,
        createdAt = createdAt,
        source = source,
        category = Category.SYSTEM,
        severity = Severity.SUCCESS,
        interruption = Interruption.SILENT,
        i18nKey = i18nKey,
    )

    @Test
    fun `same source and key collapse with the newest as representative`() {
        val groups =
            groupNotifications(
                listOf(
                    notification("a", 1),
                    notification("c", 3),
                    notification("b", 2),
                ),
            )
        assertEquals(1, groups.size)
        assertEquals("c", groups.single().representative.id)
        assertEquals(3, groups.single().count)
    }

    @Test
    fun `different keys are separate rows newest-first`() {
        val groups =
            groupNotifications(
                listOf(
                    notification("old", 1),
                    notification("new", 5, source = NotificationSource.SYNC, i18nKey = "notifications.event.sync.success"),
                ),
            )
        assertEquals(listOf("new", "old"), groups.map { it.representative.id })
    }

    @Test
    fun `a tie in createdAt is broken by id for stability`() {
        val groups = groupNotifications(listOf(notification("a", 1), notification("b", 1)))
        assertEquals("b", groups.single().representative.id)
    }

    @Test
    fun `members are retained for count and read-tracking`() {
        val groups = groupNotifications(listOf(notification("a", 1), notification("b", 2)))
        assertEquals(setOf("a", "b"), groups.single().members.map { it.id }.toSet())
    }

    @Test
    fun `empty input yields no groups`() {
        assertEquals(emptyList<NotificationGroup>(), groupNotifications(emptyList()))
    }
}
