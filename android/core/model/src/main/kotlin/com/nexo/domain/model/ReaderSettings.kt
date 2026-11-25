package com.nexo.domain.model

import androidx.compose.runtime.Immutable

/**
 * Presets for the reader's font size.
 */
enum class FontSizePreset(
    val sizePx: Int,
) {
    XS(12),
    S(14),
    SM(16),
    M(18),
    ML(20),
    L(22),
    XL(24),
    XXL(28),
    ;

    companion object {
        fun fromPx(px: Int): FontSizePreset = entries.find { it.sizePx == px } ?: M

        fun fromOrdinal(ordinal: Int): FontSizePreset = entries.getOrElse(ordinal) { M }

        /**
         * Safe lookup by enum name, used for migration from old 4-value names to new 8-value names.
         * Old names (SMALL, MEDIUM, LARGE, XLARGE) will not match and fall back to [M].
         */
        fun safeValueOf(name: String): FontSizePreset = entries.find { it.name == name } ?: M
    }
}

/**
 * Reading background / text color theme.
 * OLED is a distinct variant from DARK: bg=#0d1322ff, text=#dde2f8ff.
 */
enum class ReaderTheme(
    val bgHex: String,
    val textHex: String,
) {
    DARK("#121212", "#E2E8F0"),
    SEPIA("#F4ECD8", "#3E2723"),
    LIGHT("#FFFFFF", "#1A1A2E"),
    OLED("#0d1322", "#dde2f8"),
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): ReaderTheme = entries.getOrElse(ordinal) { DARK }
    }
}

/**
 * Line spacing preset for the EPUB WebView.
 */
enum class LineHeightPreset(
    val value: Float,
) {
    TIGHT(1.3f),
    NORMAL(1.6f),
    COMFORTABLE(1.8f),
    WIDE(2.0f),
    ;

    companion object {
        fun fromOrdinal(ordinal: Int): LineHeightPreset = entries.getOrElse(ordinal) { NORMAL }
    }
}

/**
 * Scroll mode for the reading view.
 */
enum class ScrollMode {
    VERTICAL,
    PAGINATED,
}

/**
 * Layout preferences for margins and alignment.
 *
 * [leftMargin]/[rightMargin] are px-like units that the EPUB engine turns into
 * one symmetric side margin. [verticalMargin] is dp of space above and below
 * the page, applied around the reading view (the engine has no vertical margin).
 */
@Immutable
data class LayoutPreferences(
    val leftMargin: Int = 16,
    val rightMargin: Int = 16,
    val alignment: Alignment = Alignment.JUSTIFY,
    val verticalMargin: Int = 0,
) {
    enum class Alignment {
        LEFT,
        CENTER,
        RIGHT,
        JUSTIFY,
    }
}

/**
 * User-customizable reading settings.
 *
 * New fields added during the Reader Pencil Redesign:
 * - [fontName]: [FONT_ORIGINAL], [FONT_SERIF] or [FONT_SANS] (legacy "Merriweather" reads as serif)
 * - [scrollMode]: vertical scrolling vs paginated
 * - [layoutPrefs]: margins and alignment preferences
 * - [editorValues]: keep the publisher's styles. While on, the EPUB engine
 *   ignores [lineHeight] and [LayoutPreferences.alignment].
 * - [verticalScroll]: whether vertical scroll mode is active
 */
@Immutable
data class ReaderSettings(
    val fontSize: FontSizePreset = FontSizePreset.M,
    val theme: ReaderTheme = ReaderTheme.DARK,
    val lineHeight: LineHeightPreset = LineHeightPreset.NORMAL,
    val fontName: String = FONT_SERIF,
    val scrollMode: ScrollMode = ScrollMode.VERTICAL,
    val layoutPrefs: LayoutPreferences = LayoutPreferences(),
    val editorValues: Boolean = true,
    val verticalScroll: Boolean = true,
    /** Custom highlight colour presets (5 hex values). `null` means use enum defaults. */
    val customHighlightColors: List<String>? = null,
) {
    companion object {
        const val PREFS_KEY = "reader_settings"

        /** [fontName] that keeps the publisher's own font. */
        const val FONT_ORIGINAL = "Original"

        /** [fontName] for a serif face; the legacy default value, kept so saved settings still match. */
        const val FONT_SERIF = "Georgia"

        /** [fontName] for a sans-serif face. */
        const val FONT_SANS = "Arial"
    }
}
