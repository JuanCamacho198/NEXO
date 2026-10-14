package com.nexo.presentation.screen

import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.LineHeightPreset
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import com.nexo.domain.notification.Category
import com.nexo.domain.notification.Interruption
import com.nexo.domain.notification.Notification
import com.nexo.domain.notification.NotificationSource
import com.nexo.domain.notification.NotificationTarget
import com.nexo.domain.notification.NotificationRouteTarget
import com.nexo.domain.notification.Severity
import com.nexo.domain.notification.groupNotifications
import com.nexo.domain.notification.notificationGroupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Settings-related models.
 * SettingsScreen itself is a pure Composable with no ViewModel,
 * so we test the data models used by the settings UI.
 */
class SettingsTests {
    // ── Notification tray grouping (replaces the removed mock NotificationItem) ──

    private fun notification(
        id: String,
        createdAt: Long,
        source: NotificationSource = NotificationSource.IMPORT,
        i18nKey: String = "notifications.event.import.success",
        readAt: Long? = null,
    ) = Notification(
        id = id,
        createdAt = createdAt,
        source = source,
        category = Category.SYSTEM,
        severity = Severity.SUCCESS,
        interruption = Interruption.SILENT,
        i18nKey = i18nKey,
        readAt = readAt,
    )

    @Test
    fun `grouping key combines source and i18n key`() {
        assertEquals(
            "IMPORT::notifications.event.import.success",
            notificationGroupKey(notification("a", 1)),
        )
    }

    @Test
    fun `same source and key collapse into one group whose representative is the newest`() {
        val groups =
            groupNotifications(
                listOf(
                    notification("old", createdAt = 100),
                    notification("new", createdAt = 300),
                    notification("mid", createdAt = 200),
                ),
            )
        assertEquals(1, groups.size)
        assertEquals("new", groups.single().representative.id)
        assertEquals(3, groups.single().count)
    }

    @Test
    fun `distinct keys stay separate and groups are newest-first`() {
        val groups =
            groupNotifications(
                listOf(
                    notification("sync", createdAt = 500, source = NotificationSource.SYNC, i18nKey = "notifications.event.sync.success"),
                    notification("import", createdAt = 100),
                ),
            )
        assertEquals(listOf("sync", "import"), groups.map { it.representative.id })
    }

    @Test
    fun `a group is unread when any member is unread`() {
        val read = notification("read", createdAt = 100, readAt = 999)
        val unread = notification("unread", createdAt = 200)
        assertTrue(groupNotifications(listOf(read, unread)).single().isUnread)
        assertFalse(groupNotifications(listOf(read)).single().isUnread)
    }

    @Test
    fun `an empty tray groups to an empty list`() {
        assertTrue(groupNotifications(emptyList()).isEmpty())
    }

    @Test
    fun `notification carries a route target through grouping`() {
        val withTarget =
            notification("routed", createdAt = 1).copy(
                target = NotificationTarget.Route(NotificationRouteTarget.LIBRARY),
            )
        val representative = groupNotifications(listOf(withTarget)).single().representative
        assertEquals(NotificationTarget.Route(NotificationRouteTarget.LIBRARY), representative.target)
    }

    // ── ReaderSettings model ────────────────────────────────────────

    @Test
    fun `readerSettings uses defaults`() {
        val settings = ReaderSettings()
        assertEquals(FontSizePreset.M, settings.fontSize)
        assertEquals(ReaderTheme.DARK, settings.theme)
        assertEquals(LineHeightPreset.NORMAL, settings.lineHeight)
    }

    @Test
    fun `readerSettings can be customized`() {
        val settings =
            ReaderSettings(
                fontSize = FontSizePreset.XL,
                theme = ReaderTheme.SEPIA,
                lineHeight = LineHeightPreset.COMFORTABLE,
            )
        assertEquals(FontSizePreset.XL, settings.fontSize)
        assertEquals(ReaderTheme.SEPIA, settings.theme)
        assertEquals(LineHeightPreset.COMFORTABLE, settings.lineHeight)
    }
}
