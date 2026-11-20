package com.nexo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val NexoDarkColorScheme =
    darkColorScheme(
        primary = PrimaryBlue,
        onPrimary = TextPrimary,
        secondary = AccentYellow,
        tertiary = ColorTertiary,
        background = BgMain,
        surface = BgSurface,
        surfaceContainer = BgSurface,
        surfaceVariant = BgCardHover,
        outline = BorderSubtle,
        error = ErrorSoft,
        onBackground = TextPrimary,
        onSurface = TextPrimary,
        onSurfaceVariant = TextSecondary,
        onError = ColorNeutral,
    )

// Every slot a Material component reads is mapped to a NEXO light token. Left
// unset, M3 fills them with its lavender defaults (surfaceVariant, containers,
// outlineVariant), which is why sheets, menus and chips looked off-palette and
// the page itself was pure white instead of `BgMainLight`.
private val NexoLightColorScheme =
    lightColorScheme(
        primary = PrimaryBlue,
        onPrimary = TextPrimary,
        primaryContainer = IconBgBlueLight,
        onPrimaryContainer = PrimaryBlueHover,
        secondary = AccentOrangeLight,
        onSecondary = TextPrimary,
        secondaryContainer = BgYellowTransparentLight,
        onSecondaryContainer = TextPrimaryLight,
        tertiary = ColorTertiary,
        background = BgMainLight,
        onBackground = TextPrimaryLight,
        surface = BgSurfaceLight,
        onSurface = TextPrimaryLight,
        surfaceVariant = BgCardHoverLight,
        onSurfaceVariant = TextSecondaryLight,
        surfaceTint = Color.Transparent,
        surfaceBright = BgSurfaceLight,
        surfaceDim = BgCardHoverLight,
        surfaceContainerLowest = BgSurfaceLight,
        surfaceContainerLow = BgSurfaceLight,
        surfaceContainer = BgSurfaceLight,
        surfaceContainerHigh = BgSurfaceLight,
        surfaceContainerHighest = BgCardHoverLight,
        outline = Color(0xFFCFC6B8),
        outlineVariant = BorderSubtleLight,
        error = Color(0xFFCF222E),
        onError = TextPrimary,
    )

/**
 * NexoTheme — Root theme composable. Wrap your app's content with this
 * to apply NEXO colors, typography, shapes, and [ExtendedColors].
 *
 * Behavior:
 * - Adapts to the system dark/light setting via [darkTheme] (defaults to
 *   [isSystemInDarkTheme]) — re-evaluates when the user toggles the system theme.
 * - Provides [ExtendedColors] via [LocalExtendedColors] so the Reader background
 *   and Statistics chart accents resolve to theme-appropriate values.
 *
 * @param darkTheme `true` to force dark scheme, `false` for light. Defaults to system.
 * @param content The composable hierarchy that will receive this theme.
 *
 * @see NexoTheme.colors for accessing the active [ExtendedColors] from a child composable.
 */
@Composable
fun NexoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) NexoDarkColorScheme else NexoLightColorScheme
    val extendedColors = if (darkTheme) darkExtendedColors else lightExtendedColors
    val resolvedNexoColors = if (darkTheme) darkNexoColors else lightNexoColors

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors,
        LocalNexoColors provides resolvedNexoColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NexoTypography,
            shapes = NexoShapes,
            content = content,
        )
    }
}

/**
 * NexoTheme — Companion object exposing theme-level accessors.
 *
 * Use `NexoTheme.colors` from any composable inside a [NexoTheme] to
 * read the active [ExtendedColors] (reading background, chart accent, header,
 * border) without re-supplying the [LocalExtendedColors] lookup.
 */
object NexoTheme {
    /** The active [ExtendedColors] for the current theme. Must be called from a composable inside [NexoTheme]. */
    val colors: ExtendedColors
        @Composable
        get() = LocalExtendedColors.current
}
