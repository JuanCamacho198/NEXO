package com.nexo.presentation.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extended palette tokens that don't fit Material 3's standard color slots.
 *
 * Access from a Composable via `NexoTheme.colors` (see [NexoTheme.colors])
 * or directly via [LocalExtendedColors.current] when a non-composable context is needed.
 *
 * Used by: Reader screen background, statistics charts, app header.
 *
 * @property readingBackground Page-color for the Reader (page surface, not the surrounding chrome).
 * @property chartAccent Accent color for the Statistics screen charts and insights.
 * @property bgHeader Top app bar / header strip background.
 * @property borderSubtle Hairline dividers and outline strokes.
 * @property welcomeBrandBlue Welcome/brand blue (logo circle + Google button chrome; light = PrimaryBlue, dark = WelcomeBrandBlueDark).
 */
@Immutable
data class ExtendedColors(
    val readingBackground: Color,
    val chartAccent: Color,
    val bgHeader: Color,
    val borderSubtle: Color,
    val welcomeBrandBlue: Color,
)

/**
 * CompositionLocal providing the active [ExtendedColors] for the current theme.
 *
 * Reads as `LocalExtendedColors.current` (or `NexoTheme.colors` from a Composable).
 * The default value uses [Color.Unspecified] — consumers must be inside a
 * [NexoTheme] to read real values; using [Color.Unspecified] will fall back
 * to whatever Material 3 supplies for that role.
 */
val LocalExtendedColors =
    staticCompositionLocalOf {
        ExtendedColors(
            readingBackground = Color.Unspecified,
            chartAccent = Color.Unspecified,
            bgHeader = Color.Unspecified,
            borderSubtle = Color.Unspecified,
            welcomeBrandBlue = Color.Unspecified,
        )
    }

/**
 * Dark-theme [ExtendedColors] — applied when [NexoTheme] is rendering with `darkTheme = true`.
 */
val darkExtendedColors =
    ExtendedColors(
        readingBackground = ReadingBackgroundDark,
        chartAccent = ChartAccent,
        bgHeader = BgHeader,
        borderSubtle = BorderSubtle,
        welcomeBrandBlue = WelcomeBrandBlueDark,
    )

/**
 * Light-theme [ExtendedColors] — applied when [NexoTheme] is rendering with `darkTheme = false`.
 */
val lightExtendedColors =
    ExtendedColors(
        readingBackground = ReadingBackgroundLight,
        chartAccent = ChartAccent,
        bgHeader = BgHeader,
        borderSubtle = BorderSubtle,
        welcomeBrandBlue = PrimaryBlue,
    )
