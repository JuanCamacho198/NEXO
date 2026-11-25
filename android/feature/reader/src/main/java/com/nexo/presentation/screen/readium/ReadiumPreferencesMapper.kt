package com.nexo.presentation.screen.readium

import com.nexo.domain.model.LayoutPreferences
import com.nexo.domain.model.ReaderSettings
import com.nexo.domain.model.ReaderTheme
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme

/** Readium page-margin factor matching the legacy fixed value at the default 16px margins. */
private const val DEFAULT_PAGE_MARGINS = 1.4
private const val DEFAULT_MARGIN_PX = 16.0
private const val MAX_PAGE_MARGINS = 4.0

/**
 * Maps [ReaderSettings] to Readium's [EpubPreferences] for
 * [org.readium.r2.navigator.epub.EpubNavigatorFragment.submitPreferences].
 *
 * - `publisherStyles` follows [ReaderSettings.editorValues]. Readium applies
 *   line height and text alignment only while publisher styles are off, so
 *   those two settings take effect exactly when the reader turns the book's
 *   own style off.
 * - Background and text colours are passed explicitly so the page matches the
 *   swatch the reader picked (OLED is a dark theme with its own colours).
 * - Readium has one symmetric margin; the average of the left/right margins
 *   is scaled so the default 16px keeps the previous 1.4 factor.
 */
fun ReaderSettings.toEpubPreferences(): EpubPreferences =
    EpubPreferences(
        fontSize =
            when (fontSize) {
                com.nexo.domain.model.FontSizePreset.XS -> 0.75
                com.nexo.domain.model.FontSizePreset.S -> 0.875
                com.nexo.domain.model.FontSizePreset.SM -> 1.0
                com.nexo.domain.model.FontSizePreset.M -> 1.125
                com.nexo.domain.model.FontSizePreset.ML -> 1.25
                com.nexo.domain.model.FontSizePreset.L -> 1.5
                com.nexo.domain.model.FontSizePreset.XL -> 1.75
                com.nexo.domain.model.FontSizePreset.XXL -> 2.0
            },
        fontFamily =
            when (fontName.lowercase()) {
                ReaderSettings.FONT_ORIGINAL.lowercase() -> null
                "sans-serif", ReaderSettings.FONT_SANS.lowercase() -> ReadiumFontFamily.SANS_SERIF
                else -> ReadiumFontFamily.SERIF
            },
        theme =
            when (theme) {
                ReaderTheme.DARK, ReaderTheme.OLED -> ReadiumTheme.DARK
                ReaderTheme.SEPIA -> ReadiumTheme.SEPIA
                ReaderTheme.LIGHT -> ReadiumTheme.LIGHT
            },
        backgroundColor = readiumColor(theme.bgHex),
        textColor = readiumColor(theme.textHex),
        lineHeight = lineHeight.value.toDouble(),
        textAlign =
            when (layoutPrefs.alignment) {
                LayoutPreferences.Alignment.LEFT -> TextAlign.START
                LayoutPreferences.Alignment.CENTER -> TextAlign.CENTER
                LayoutPreferences.Alignment.RIGHT -> TextAlign.END
                LayoutPreferences.Alignment.JUSTIFY -> TextAlign.JUSTIFY
            },
        scroll = scrollMode == com.nexo.domain.model.ScrollMode.VERTICAL || verticalScroll,
        publisherStyles = editorValues,
        pageMargins = pageMarginsFor(layoutPrefs),
    )

internal fun pageMarginsFor(layout: LayoutPreferences): Double {
    val averagePx = (layout.leftMargin + layout.rightMargin) / 2.0
    return (averagePx / DEFAULT_MARGIN_PX * DEFAULT_PAGE_MARGINS).coerceIn(0.0, MAX_PAGE_MARGINS)
}

private fun readiumColor(hex: String): ReadiumColor? =
    runCatching {
        val clean = hex.removePrefix("#")
        ReadiumColor((if (clean.length == 6) "FF$clean" else clean).toLong(16).toInt())
    }.getOrNull()

/**
 * Builds a [EpubNavigatorFactory.Configuration] from [ReaderSettings].
 * @deprecated Use [toEpubPreferences] instead.
 */
@Deprecated("Use toEpubPreferences()", ReplaceWith("settings.toEpubPreferences()"))
fun buildNavigatorConfig(settings: ReaderSettings): EpubNavigatorFactory.Configuration {
    // Delegate to mapper to keep mapping single-sourced; result not embedded in Configuration
    // but call ensures future changes to preferences stay coordinated.
    settings.toEpubPreferences()
    return EpubNavigatorFactory.Configuration(
        defaults =
            EpubDefaults(
                pageMargins = 1.4,
            ),
    )
}
