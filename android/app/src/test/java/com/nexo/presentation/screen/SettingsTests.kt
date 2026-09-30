package com.nexo.presentation.screen

import androidx.compose.ui.graphics.vector.ImageVector
import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.LineHeightPreset
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import com.nexo.ui.components.molecules.NotificationItem
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for Settings-related models.
 * SettingsScreen itself is a pure Composable with no ViewModel,
 * so we test the data models used by the settings UI.
 */
class SettingsTests {
    // ── NotificationItem model ──────────────────────────────────────

    @Test
    fun `notificationItem defaults to unread`() {
        val item =
            NotificationItem(
                id = "test-1",
                icon = mockk<ImageVector>(),
                title = "Test",
                body = "Body",
            )
        assertEquals("test-1", item.id)
        assertEquals("Test", item.title)
        assertEquals("Body", item.body)
        assertEquals(true, item.isUnread)
    }

    @Test
    fun `notificationItem can be created as read`() {
        val item =
            NotificationItem(
                id = "test-2",
                icon = mockk<ImageVector>(),
                title = "Read",
                body = "Body",
                isUnread = false,
            )
        assertEquals(false, item.isUnread)
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
