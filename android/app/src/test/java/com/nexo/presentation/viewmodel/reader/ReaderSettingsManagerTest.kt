package com.nexo.presentation.viewmodel.reader

import com.nexo.data.session.ReaderPreferences
import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSettingsManagerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `init loads persisted settings via ReaderPreferences`() {
        val customSettings =
            ReaderSettings(
                fontSize = FontSizePreset.XL,
                theme = ReaderTheme.SEPIA,
            )
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns customSettings
            }

        val manager = ReaderSettingsManager(mockPrefs)

        verify { mockPrefs.load() }
        assertEquals("Font size should be XL", FontSizePreset.XL, manager.state.value.readerSettings.fontSize)
        assertEquals("Theme should be SEPIA", ReaderTheme.SEPIA, manager.state.value.readerSettings.theme)
    }

    @Test
    fun `updateReaderSettings updates state and persists`() {
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns ReaderSettings()
            }
        val manager = ReaderSettingsManager(mockPrefs)
        val newSettings =
            ReaderSettings(
                fontSize = FontSizePreset.XXL,
                theme = ReaderTheme.LIGHT,
            )

        manager.updateReaderSettings(newSettings)

        assertEquals(newSettings, manager.state.value.readerSettings)
        verify { mockPrefs.save(newSettings) }
    }

    @Test
    fun `onToggleSplitSettings flips boolean`() {
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns ReaderSettings()
            }
        val manager = ReaderSettingsManager(mockPrefs)

        assertFalse("Split settings should start hidden", manager.state.value.showSplitSettings)

        manager.onToggleSplitSettings()
        assertTrue("Split settings should be visible after toggle", manager.state.value.showSplitSettings)

        manager.onToggleSplitSettings()
        assertFalse("Split settings should be hidden after second toggle", manager.state.value.showSplitSettings)
    }

    @Test
    fun `onUpdateCustomHighlightColor mutates palette and persists`() {
        val initialColors = listOf("#000000", "#111111", "#222222", "#333333", "#444444")
        val initialSettings = ReaderSettings(customHighlightColors = initialColors)
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns initialSettings
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onUpdateCustomHighlightColor(1, "#FF0000")

        val expectedColors = listOf("#000000", "#FF0000", "#222222", "#333333", "#444444")
        assertEquals(expectedColors, manager.state.value.readerSettings.customHighlightColors)

        val expectedSettings = ReaderSettings(customHighlightColors = expectedColors)
        verify { mockPrefs.save(expectedSettings) }
    }

    @Test
    fun `onUpdateCustomHighlightColor with out-of-bounds index is no-op`() {
        val initialColors = listOf("#000000", "#111111", "#222222", "#333333", "#444444")
        val initialSettings = ReaderSettings(customHighlightColors = initialColors)
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns initialSettings
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onUpdateCustomHighlightColor(10, "#FF0000")

        assertEquals(initialColors, manager.state.value.readerSettings.customHighlightColors)
    }

    @Test
    fun `onUpdateCustomHighlightColor with null palette initializes defaults then mutates`() {
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns ReaderSettings()
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onUpdateCustomHighlightColor(0, "#FF0000")

        val colors = manager.state.value.readerSettings.customHighlightColors
        assertNotNull("customHighlightColors should be initialized from defaults", colors)
        assertEquals("#FF0000", colors!![0])
    }

    @Test
    fun `onAddCustomHighlightColor adds color when below max`() {
        val initialColors = listOf("#000000", "#111111", "#222222")
        val initialSettings = ReaderSettings(customHighlightColors = initialColors)
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns initialSettings
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onAddCustomHighlightColor()

        val colors = manager.state.value.readerSettings.customHighlightColors
        assertEquals(4, colors?.size)
        assertEquals("#FACC15", colors?.last()) // YELLOW default
    }

    @Test
    fun `onAddCustomHighlightColor no-ops when at max (5)`() {
        val initialColors = listOf("#000000", "#111111", "#222222", "#333333", "#444444")
        val initialSettings = ReaderSettings(customHighlightColors = initialColors)
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns initialSettings
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onAddCustomHighlightColor()

        val colors = manager.state.value.readerSettings.customHighlightColors
        assertEquals(5, colors?.size)
    }

    @Test
    fun `onDeleteCustomHighlightColor removes color when above min`() {
        val initialColors = listOf("#000000", "#111111", "#222222", "#333333")
        val initialSettings = ReaderSettings(customHighlightColors = initialColors)
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns initialSettings
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onDeleteCustomHighlightColor(1)

        val colors = manager.state.value.readerSettings.customHighlightColors
        assertEquals(3, colors?.size)
        assertEquals("#000000", colors!![0])
        assertEquals("#222222", colors[1])
        assertEquals("#333333", colors[2])
    }

    @Test
    fun `onDeleteCustomHighlightColor no-ops when at min (3)`() {
        val initialColors = listOf("#000000", "#111111", "#222222")
        val initialSettings = ReaderSettings(customHighlightColors = initialColors)
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns initialSettings
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onDeleteCustomHighlightColor(0)

        val colors = manager.state.value.readerSettings.customHighlightColors
        assertEquals(3, colors?.size)
    }

    @Test
    fun `onDeleteCustomHighlightColor with null palette initializes then deletes`() {
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns ReaderSettings()
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onDeleteCustomHighlightColor(0)

        val colors = manager.state.value.readerSettings.customHighlightColors
        assertNotNull("customHighlightColors should be initialized from defaults", colors)
        assertEquals(4, colors?.size) // 5 default entries, removed at index 0
    }

    @Test
    fun `onResetCustomHighlightColors resets palette`() {
        val initialColors = listOf("#000000", "#111111", "#222222", "#333333", "#444444")
        val initialSettings = ReaderSettings(customHighlightColors = initialColors)
        val mockPrefs =
            mockk<ReaderPreferences>(relaxed = true) {
                every { load() } returns initialSettings
            }
        val manager = ReaderSettingsManager(mockPrefs)

        manager.onResetCustomHighlightColors()

        val defaultReaderSettings = ReaderSettings()
        assertEquals(
            defaultReaderSettings.customHighlightColors,
            manager.state.value.readerSettings.customHighlightColors,
        )

        val expectedSaved = ReaderSettings(customHighlightColors = null)
        verify { mockPrefs.save(expectedSaved) }
    }
}
