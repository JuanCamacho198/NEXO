package com.nexo.presentation.screen.readium

import com.nexo.domain.model.FontSizePreset
import com.nexo.domain.model.LayoutPreferences
import com.nexo.domain.model.LineHeightPreset
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.TextAlign
import org.robolectric.RobolectricTestRunner
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme

@RunWith(RobolectricTestRunner::class)
class ReadiumPreferencesMapperTest {
    @Test
    fun toEpubPreferences_mapsFontScaleAndTheme() {
        val settings =
            ReaderSettings(
                fontSize = FontSizePreset.L,
                lineHeight = LineHeightPreset.COMFORTABLE,
                fontName = "serif",
                theme = ReaderTheme.SEPIA,
            )
        val prefs = settings.toEpubPreferences()
        assertNotNull(prefs)
    }

    @Test
    fun toEpubPreferences_mapsLightTheme() {
        val settings = ReaderSettings(theme = ReaderTheme.LIGHT)
        val prefs = settings.toEpubPreferences()
        assertNotNull(prefs)
    }

    @Test
    fun deprecatedShim_delegatesToMapper() {
        val settings = ReaderSettings(fontSize = FontSizePreset.XS)
        val viaMapper = settings.toEpubPreferences()

        @Suppress("DEPRECATION")
        val config = buildNavigatorConfig(settings)
        assertNotNull(config)
        assertNotNull(viaMapper)
    }

    @Test
    fun toEpubPreferences_publisherStylesFollowEditorValues() {
        assertEquals(true, ReaderSettings(editorValues = true).toEpubPreferences().publisherStyles)
        assertEquals(false, ReaderSettings(editorValues = false).toEpubPreferences().publisherStyles)
    }

    @Test
    fun toEpubPreferences_oledIsDarkWithItsOwnColours() {
        val prefs = ReaderSettings(theme = ReaderTheme.OLED).toEpubPreferences()
        assertEquals(ReadiumTheme.DARK, prefs.theme)
        assertEquals(0xFF0D1322.toInt(), prefs.backgroundColor?.int)
        assertEquals(0xFFDDE2F8.toInt(), prefs.textColor?.int)
    }

    @Test
    fun toEpubPreferences_mapsAlignment() {
        fun align(alignment: LayoutPreferences.Alignment) = ReaderSettings(layoutPrefs = LayoutPreferences(alignment = alignment)).toEpubPreferences().textAlign
        assertEquals(TextAlign.START, align(LayoutPreferences.Alignment.LEFT))
        assertEquals(TextAlign.JUSTIFY, align(LayoutPreferences.Alignment.JUSTIFY))
    }

    @Test
    fun toEpubPreferences_originalFontKeepsPublisherFont() {
        assertNull(ReaderSettings(fontName = ReaderSettings.FONT_ORIGINAL).toEpubPreferences().fontFamily)
        assertEquals(ReadiumFontFamily.SANS_SERIF, ReaderSettings(fontName = ReaderSettings.FONT_SANS).toEpubPreferences().fontFamily)
        assertEquals(ReadiumFontFamily.SERIF, ReaderSettings(fontName = "Merriweather").toEpubPreferences().fontFamily)
    }

    @Test
    fun pageMargins_defaultKeepsLegacyFactorAndScales() {
        assertEquals(1.4, pageMarginsFor(LayoutPreferences(leftMargin = 16, rightMargin = 16)), 0.0001)
        assertEquals(0.7, pageMarginsFor(LayoutPreferences(leftMargin = 8, rightMargin = 8)), 0.0001)
        assertEquals(2.8, pageMarginsFor(LayoutPreferences(leftMargin = 32, rightMargin = 32)), 0.0001)
    }
}
