package com.nexo.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic design tokens for NEXO, resolved per theme.
 *
 * New code should reference these tokens instead of raw [Color] literals.
 * Legacy aliases in [Color.kt] remain for backward compatibility with
 * reader-specific code that predates this type.
 *
 * Access from a Composable via [nexoColors] (or [LocalNexoColors.current]) so the
 * active light/dark palette is resolved automatically. The concrete palettes are
 * [darkNexoColors] and [lightNexoColors].
 */
@Immutable
data class NexoColors(
    // ─── Backgrounds ──────────────────────────────────────────────
    /** App-wide canvas background. Dark: [BgMain]. */
    val background: Color,
    /** Elevated card/panel background (one step above [background]). Dark: [BgSurface]. */
    val surface: Color,
    /** Card hover/pressed state background. Dark: [BgCardHover]. */
    val surfaceVariant: Color,
    /** Top app bar / header strip background. Dark: [BgHeader]. */
    val header: Color,
    // ─── Borders ──────────────────────────────────────────────────
    /** Hairline dividers and outline strokes. Dark: [BorderSubtle]. */
    val borderSubtle: Color,
    // ─── Primary / Brand ─────────────────────────────────────────
    /** Main brand interactive color (buttons, links, focused controls). Dark: [PrimaryBlue]. */
    val primary: Color,
    /** Pressed-state variant of [primary]. Dark: [PrimaryBlueHover]. */
    val primaryHover: Color,
    /** Background tint for icon containers. Dark: [IconBgBlue]. */
    val iconBackground: Color,
    // ─── Text ────────────────────────────────────────────────────
    /** Primary text/icon color. Dark: [TextPrimary]. */
    val textPrimary: Color,
    /** De-emphasized text (captions, metadata, helper text). Dark: [TextSecondary]. */
    val textSecondary: Color,
    /** Colored inline links and accent text. Dark: [TextAccent]. */
    val textAccent: Color,
    // ─── Status / Category ───────────────────────────────────────
    /** Warning/attention category color. Dark: [AccentYellow]. */
    val accentYellow: Color,
    /**
     * Primary Home accent — warmer amber than [accentYellow].
     *
     * Dark: [AccentOrange]. Drives the Home hero CTA, the continue-reading
     * play button and progress fill, and the genre chips.
     */
    val accentOrange: Color,
    /** Background tint for yellow-accented chips/badges. Dark: [BgYellowTransparent]. */
    val backgroundYellowTransparent: Color,
    /** Reading-streak indicator (flame glyph in the top bar). Dark: [AccentYellow]. */
    val streak: Color,
    /** Success/positive category color ("Completed"). Dark: [AccentGreen]. */
    val accentGreen: Color,
    /** Background tint for green-accented chips/badges. Dark: [BgGreenTransparent]. */
    val backgroundGreenTransparent: Color,
    /** Highlight/category color used in stats and insights. Dark: [AccentPurple]. */
    val accentPurple: Color,
    /** Background tint for purple-accented chips/badges. Dark: [BgPurpleTransparent]. */
    val backgroundPurpleTransparent: Color,
    /** Information/neutral category color. Dark: [AccentBlue]. */
    val accentBlue: Color,
    /** Background tint for blue-accented chips/badges. Dark: [BgBlueTransparent]. */
    val backgroundBlueTransparent: Color,
    // ─── Navigation ──────────────────────────────────────────────
    /** Foreground color for the selected bottom-nav item. Dark: [NavBarActive]. */
    val navBarActive: Color,
    /** Foreground color for unselected bottom-nav items. Dark: [NavBarInactive]. */
    val navBarInactive: Color,
    /** Translucent overlay for the floating action area on the navbar. Dark: [NavBarOverlay]. */
    val navBarOverlay: Color,
    // ─── Utility ────────────────────────────────────────────────
    /** Outline/stroke color for dark-theme surfaces. Dark: [OutlineDark]. */
    val outline: Color,
    /** Soft red used for non-blocking errors and validation messages. Dark: [ErrorSoft]. */
    val errorSoft: Color,
    /** Light reading surface for the Reader (day mode). */
    val readingBackgroundLight: Color,
    /** Dark reading surface for the Reader (night mode). */
    val readingBackgroundDark: Color,
)

/**
 * CompositionLocal providing the active [NexoColors] palette for the current theme.
 *
 * Reads as `LocalNexoColors.current` (or [nexoColors] from a Composable).
 * Defaults to [darkNexoColors] so consumers outside a [NexoTheme] keep the
 * historical dark-only rendering instead of degrading to unspecified colors.
 *
 * Declared as `compositionLocalOf`, not `staticCompositionLocalOf`: the active
 * palette changes at runtime when the user switches theme, and every reader must
 * be invalidated. A static local would leave readers on the previous palette.
 */
val LocalNexoColors = compositionLocalOf { darkNexoColors }

/**
 * Dark-theme [NexoColors] — applied when [NexoTheme] is rendering with `darkTheme = true`.
 *
 * Values are byte-identical to the palette NEXO shipped when it was dark-only.
 */
val darkNexoColors =
    NexoColors(
        background = BgMain,
        surface = BgSurface,
        surfaceVariant = BgCardHover,
        header = BgHeader,
        borderSubtle = BorderSubtle,
        primary = PrimaryBlue,
        primaryHover = PrimaryBlueHover,
        iconBackground = IconBgBlue,
        textPrimary = TextPrimary,
        textSecondary = TextSecondary,
        textAccent = TextAccent,
        accentYellow = AccentYellow,
        accentOrange = AccentOrange,
        backgroundYellowTransparent = BgYellowTransparent,
        streak = AccentYellow,
        accentGreen = AccentGreen,
        backgroundGreenTransparent = BgGreenTransparent,
        accentPurple = AccentPurple,
        backgroundPurpleTransparent = BgPurpleTransparent,
        accentBlue = AccentBlue,
        backgroundBlueTransparent = BgBlueTransparent,
        navBarActive = com.nexo.presentation.theme.NavBarActive,
        navBarInactive = com.nexo.presentation.theme.NavBarInactive,
        navBarOverlay = com.nexo.presentation.theme.NavBarOverlay,
        outline = OutlineDark,
        errorSoft = ErrorSoft,
        readingBackgroundLight = com.nexo.presentation.theme.ReadingBackgroundLight,
        readingBackgroundDark = com.nexo.presentation.theme.ReadingBackgroundDark,
    )

/**
 * Light-theme [NexoColors] — applied when [NexoTheme] is rendering with `darkTheme = false`.
 *
 * Values come from the `.pen` theme-mode variables. Brand accents are shared with
 * the dark palette; backgrounds, text and translucent tints invert.
 *
 * Tokens with no light variable in the design source (inferred from the nearest
 * light token): [header] (`bg_main`), [navBarActive] (`primary`),
 * [navBarInactive] (`text_secondary`), [navBarOverlay] (`primary` at 8% alpha)
 * and [outline] (`border`).
 */
val lightNexoColors =
    NexoColors(
        background = BgMainLight,
        surface = BgSurfaceLight,
        surfaceVariant = BgCardHoverLight,
        header = BgHeaderLight,
        borderSubtle = BorderSubtleLight,
        primary = PrimaryBlue,
        primaryHover = PrimaryBlueHover,
        iconBackground = IconBgBlueLight,
        textPrimary = TextPrimaryLight,
        textSecondary = TextSecondaryLight,
        textAccent = TextAccent,
        accentYellow = AccentYellow,
        accentOrange = AccentOrange,
        backgroundYellowTransparent = BgYellowTransparentLight,
        streak = AccentYellow,
        accentGreen = AccentGreen,
        backgroundGreenTransparent = BgGreenTransparentLight,
        accentPurple = AccentPurple,
        backgroundPurpleTransparent = BgPurpleTransparentLight,
        accentBlue = AccentBlue,
        backgroundBlueTransparent = BgBlueTransparentLight,
        navBarActive = NavBarActiveLight,
        navBarInactive = NavBarInactiveLight,
        navBarOverlay = NavBarOverlayLight,
        outline = OutlineLight,
        errorSoft = ErrorSoftLight,
        readingBackgroundLight = com.nexo.presentation.theme.ReadingBackgroundLight,
        readingBackgroundDark = com.nexo.presentation.theme.ReadingBackgroundDark,
    )

/** The active [NexoColors] for the current theme. Must be called from a composable inside [NexoTheme]. */
val nexoColors: NexoColors
    @Composable
    @ReadOnlyComposable
    get() = LocalNexoColors.current
