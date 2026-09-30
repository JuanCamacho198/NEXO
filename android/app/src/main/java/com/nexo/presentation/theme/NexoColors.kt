package com.nexo.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic design tokens for NEXO.
 *
 * New code should reference these tokens instead of raw [Color] literals.
 * Legacy aliases in [Color.kt] remain for backward compatibility with
 * reader-specific code that predates this object.
 */
object NexoColors {
    // ─── Backgrounds ──────────────────────────────────────────────
    /** App-wide canvas background. Alias of [BgMain]. */
    val background: Color = BgMain

    /** Elevated card/panel background (one step above [background]). Alias of [BgSurface]. */
    val surface: Color = BgSurface

    /** Card hover/pressed state background. Alias of [BgCardHover]. */
    val surfaceVariant: Color = BgCardHover

    /** Top app bar / header strip background. Alias of [BgHeader]. */
    val header: Color = BgHeader

    // ─── Borders ──────────────────────────────────────────────────
    /** Hairline dividers and outline strokes. Alias of [BorderSubtle]. */
    val borderSubtle: Color = BorderSubtle

    // ─── Primary / Brand ─────────────────────────────────────────
    /** Main brand interactive color (buttons, links, focused controls). Alias of [PrimaryBlue]. */
    val primary: Color = PrimaryBlue

    /** Pressed-state variant of [primary]. Alias of [PrimaryBlueHover]. */
    val primaryHover: Color = PrimaryBlueHover

    /** Darker blue background tint for icon containers. Alias of [IconBgBlue]. */
    val iconBackground: Color = IconBgBlue

    // ─── Text ────────────────────────────────────────────────────
    /** Primary text/icon color. Alias of [TextPrimary]. */
    val textPrimary: Color = TextPrimary

    /** De-emphasized text (captions, metadata, helper text). Alias of [TextSecondary]. */
    val textSecondary: Color = TextSecondary

    /** Colored inline links and accent text. Alias of [TextAccent]. */
    val textAccent: Color = TextAccent

    // ─── Status / Category ───────────────────────────────────────
    /** Warning/attention category color. Alias of [AccentYellow]. */
    val accentYellow: Color = AccentYellow

    /** Background tint for yellow-accented chips/badges. Alias of [BgYellowTransparent]. */
    val backgroundYellowTransparent: Color = BgYellowTransparent

    /** Success/positive category color ("Completed"). Alias of [AccentGreen]. */
    val accentGreen: Color = AccentGreen

    /** Background tint for green-accented chips/badges. Alias of [BgGreenTransparent]. */
    val backgroundGreenTransparent: Color = BgGreenTransparent

    /** Highlight/category color used in stats and insights. Alias of [AccentPurple]. */
    val accentPurple: Color = AccentPurple

    /** Background tint for purple-accented chips/badges. Alias of [BgPurpleTransparent]. */
    val backgroundPurpleTransparent: Color = BgPurpleTransparent

    /** Information/neutral category color. Alias of [AccentBlue]. */
    val accentBlue: Color = AccentBlue

    /** Background tint for blue-accented chips/badges. Alias of [BgBlueTransparent]. */
    val backgroundBlueTransparent: Color = BgBlueTransparent

    // ─── Navigation ──────────────────────────────────────────────
    /** Foreground color for the selected bottom-nav item. Alias of [NavBarActive]. */
    val navBarActive: Color = com.nexo.presentation.theme.NavBarActive

    /** Foreground color for unselected bottom-nav items. Alias of [NavBarInactive]. */
    val navBarInactive: Color = com.nexo.presentation.theme.NavBarInactive

    /** Translucent overlay for the floating action area on the navbar. Alias of [NavBarOverlay]. */
    val navBarOverlay: Color = com.nexo.presentation.theme.NavBarOverlay

    // ─── Utility ────────────────────────────────────────────────
    /** Outline/stroke color for dark-theme surfaces. Alias of [OutlineDark]. */
    val outline: Color = OutlineDark

    /** Soft red used for non-blocking errors and validation messages. Alias of [ErrorSoft]. */
    val errorSoft: Color = ErrorSoft

    /** Light reading surface for the Reader (day mode). Alias of [ReadingBackgroundLight]. */
    val readingBackgroundLight: Color = com.nexo.presentation.theme.ReadingBackgroundLight

    /** Dark reading surface for the Reader (night mode). Alias of [ReadingBackgroundDark]. */
    val readingBackgroundDark: Color = com.nexo.presentation.theme.ReadingBackgroundDark
}
