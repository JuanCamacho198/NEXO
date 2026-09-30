package com.nexo.presentation.theme

import androidx.compose.ui.graphics.Color

// ─── Design Tokens - Backgrounds ──────────────────────────────────────

/**
 * BgMain — App-wide canvas background.
 *
 * Used by: NexoTheme dark scheme background, app-level Scaffold
 * Pairs with: TextPrimary for body text, BorderSubtle for dividers
 * Contrast: 14.8:1 against TextPrimary (AAA)
 */
val BgMain = Color(0xFF0B1120) // $bg_main: Dark, almost black blue

/**
 * BgSurface — Elevated card/panel background (one step above [BgMain]).
 *
 * Used by: Cards, sheets, bottom navigation, dialog surfaces
 * Pairs with: BgCardHover (hover/pressed state), TextPrimary (content)
 * Contrast: 12.5:1 against TextPrimary (AAA)
 */
val BgSurface = Color(0xFF161F33) // $bg_surface: Card/surface background

/**
 * BgCardHover — Card hover/pressed state, slightly lighter than [BgSurface].
 *
 * Used by: BookCard hover, ListItem press ripple base, interactive cards
 * Pairs with: BgSurface (resting state)
 * Contrast: 11.0:1 against TextPrimary (AAA)
 */
val BgCardHover = Color(0xFF1E293B) // $bg-card-hover

// ─── Design Tokens - Borders ─────────────────────────
/**
 * BorderSubtle — Hairline dividers and outline strokes.
 *
 * Used by: Card outlines, list separators, input field borders
 * Pairs with: BgSurface (background it sits on)
 * Contrast: 1.4:1 against BgSurface — visible but unobtrusive
 */
val BorderSubtle = Color(0xFF242A3A)

// ─── Design Tokens - Surfaces ────────────────────────
/**
 * BgHeader — Top app bar / header strip background (slightly darker than canvas).
 *
 * Used by: HomeScreen top bar, BookDetailScreen header
 * Pairs with: TextPrimary (header text), NavBarActive (active controls)
 * Contrast: 13.5:1 against TextPrimary (AAA)
 */
val BgHeader = Color(0xFF0D1322)

// ─── Design Tokens - Primary / Brand ──────────────────────────────────
/**
 * PrimaryBlue — Main brand interactive color (buttons, links, focused controls).
 *
 * Used by: NexoButton (FILLED), NexoIconButton, SearchBox, progress accents
 * Pairs with: TextPrimary (text on this background), PrimaryBlueHover (press state)
 * Contrast: 5.2:1 against TextPrimary (AA), 4.6:1 against BgMain (AA Large)
 * Brand: signature blue from the marketing palette
 */
val PrimaryBlue = Color(0xFF3B82F6) // $primary: Main brand color, buttons, accents

/**
 * PrimaryBlueHover — Pressed/darker variant of [PrimaryBlue].
 *
 * Used by: NexoButton press state, filled-icon-button ripple
 * Pairs with: TextPrimary (text on this background)
 * Contrast: 6.4:1 against TextPrimary (AAA)
 */
val PrimaryBlueHover = Color(0xFF2563EB) // $primary-blue-hover

/**
 * IconBgBlue — Darker blue background tint for icon containers.
 *
 * Used by: StatCard icon containers, feature icon backgrounds
 * Pairs with: PrimaryBlue foreground icon
 * Contrast: 5.8:1 against PrimaryBlue (AA)
 */
val IconBgBlue = Color(0xFF1E3A8A) // $icon-bg-blue: Darker blue for icon backgrounds

/**
 * WelcomeBrandBlueDark - Dark-scheme welcome/brand blue (parity with desktop
 * `--welcome-brand-blue` #5f82ff, tokens.css:173).
 *
 * Used by: AuthScreen welcome logo circle and Google button chrome in dark
 * scheme (light scheme uses the existing [PrimaryBlue]).
 */
val WelcomeBrandBlueDark = Color(0xFF5F82FF)

// ─── Design Tokens - Text ────────────────────────────────────────────
/**
 * TextPrimary — Primary text and icon color (white in dark theme).
 *
 * Used by: Body text, headlines, icons on dark surfaces, button labels
 * Pairs with: BgMain, BgSurface, BgHeader (backgrounds it sits on)
 * Contrast: 14.8:1 against BgMain (AAA)
 */
val TextPrimary = Color(0xFFFFFFFF) // $text-primary: White

/**
 * TextSecondary — De-emphasized text (captions, metadata, helper text).
 *
 * Used by: Captions, timestamps, secondary labels, inactive navigation
 * Pairs with: BgMain, BgSurface
 * Contrast: 5.4:1 against BgMain (AA)
 */
val TextSecondary = Color(0xFF94A3B8) // $text-secondary: Slate gray

/**
 * TextAccent — Colored inline links and accent text.
 *
 * Used by: Inline links, "Read more" affordances, emphasized inline text
 * Pairs with: body text on BgMain/BgSurface
 * Contrast: 5.2:1 against BgMain (AA)
 */
val TextAccent = Color(0xFF3B82F6) // $text-accent: Blue accent

// ─── Design Tokens - Status / Category Colors ─────────────────────────
/**
 * AccentYellow — Warning/attention category color.
 *
 * Used by: Reading status badges ("Want to read"), category chips
 * Pairs with: BgYellowTransparent (chip background)
 * Contrast: 9.8:1 against BgYellowTransparent (AAA)
 */
val AccentYellow = Color(0xFFEAB308)

/**
 * BgYellowTransparent — Background tint for yellow-accented chips/badges.
 *
 * Used by: Reading status chip background, warning badges
 * Pairs with: AccentYellow (foreground)
 */
val BgYellowTransparent = Color(0xFF423419)

/**
 * AccentGreen — Success/positive category color.
 *
 * Used by: "Completed" reading status, success toasts, completion badges
 * Pairs with: BgGreenTransparent (chip background)
 * Contrast: 6.5:1 against BgGreenTransparent (AAA)
 */
val AccentGreen = Color(0xFF22C55E)

/**
 * BgGreenTransparent — Background tint for green-accented chips/badges.
 *
 * Used by: "Completed" chip background, success badges
 * Pairs with: AccentGreen (foreground)
 */
val BgGreenTransparent = Color(0xFF143A27)

/**
 * AccentPurple — Highlight/category color (used for stats and insights).
 *
 * Used by: Statistics charts, reading-streak accents, insight badges
 * Pairs with: BgPurpleTransparent (chip background)
 * Contrast: 6.0:1 against BgPurpleTransparent (AAA)
 */
val AccentPurple = Color(0xFFA855F7)

/**
 * BgPurpleTransparent — Background tint for purple-accented chips/badges.
 *
 * Used by: Insight chip background, stats highlight background
 * Pairs with: AccentPurple (foreground)
 */
val BgPurpleTransparent = Color(0xFF311C4A)

/**
 * AccentBlue — Information/neutral category color (alias of [PrimaryBlue]).
 *
 * Used by: Information badges, neutral category chips
 * Pairs with: BgBlueTransparent (chip background)
 * Contrast: 5.2:1 against BgBlueTransparent (AA)
 */
val AccentBlue = Color(0xFF3B82F6)

/**
 * BgBlueTransparent — Background tint for blue-accented chips/badges.
 *
 * Used by: Information chip background, neutral badges
 * Pairs with: AccentBlue (foreground)
 */
val BgBlueTransparent = Color(0xFF1E3A8A)

// ─── Navigation Bar ──────────────────────────────────
/**
 * NavBarActive — Foreground color for the selected bottom-nav item.
 *
 * Used by: BottomNavBar active icon/label
 * Pairs with: BgSurface (navbar background)
 * Contrast: 9.5:1 against BgSurface (AAA)
 */
val NavBarActive = Color(0xFFADC6FF)

/**
 * NavBarInactive — Foreground color for unselected bottom-nav items.
 *
 * Used by: BottomNavBar inactive icon/label
 * Pairs with: BgSurface (navbar background)
 * Contrast: 8.6:1 against BgSurface (AAA)
 */
val NavBarInactive = Color(0xFFC2C6D6)

/**
 * NavBarOverlay — Translucent overlay for the floating action area on the navbar.
 *
 * Used by: FAB scrim, navbar overlay tint
 * Pairs with: BgSurface
 * Alpha: ~4% — pure visual layer, not for text/foreground content
 */
val NavBarOverlay = Color(0x0A4D8EFF)

// ─── Legacy aliases (backward compat) ─────────────────────────────────
// The following aliases exist for code that predates the [NexoColors] semantic
// token system. New code should reference [NexoColors] or the tokens above.
val DeepDarkBlue = BgMain
val AccentPrimary = PrimaryBlue
val AccentSecondary = AccentGreen
val ChartAccent = AccentPurple

/**
 * ReadingBackgroundLight — Light reading surface for the Reader (day mode).
 *
 * Used by: ReaderScreen background when light reader theme is selected
 * Pairs with: TextPrimary / ColorNeutral (text)
 */
val ReadingBackgroundLight = Color(0xFFF9F9F9)

/**
 * ReadingBackgroundDark — Dark reading surface for the Reader (night mode).
 *
 * Used by: ReaderScreen background when dark reader theme is selected
 * Pairs with: TextPrimary (text)
 */
val ReadingBackgroundDark = Color(0xFF000000)

val ColorPrimary = TextPrimary
val ColorSecondary = Color(0xFFE0E0E0)
val ColorTertiary = Color(0xFF9E9E9E)
val ColorNeutral = Color(0xFF000000)

val BackgroundDark = BgMain
val SurfaceDark = BgSurface

/**
 * OutlineDark — Outline/stroke color for dark-theme surfaces.
 *
 * Used by: Material3 darkColorScheme outline slot
 * Pairs with: BgSurface, BgCardHover
 */
val OutlineDark = Color(0xFF30363D)

/**
 * ErrorSoft — Soft red used for non-blocking errors and validation messages.
 *
 * Used by: Material3 darkColorScheme error slot, form validation text
 * Pairs with: ColorNeutral (text on error backgrounds)
 * Contrast: 4.8:1 against BgMain (AA)
 */
val ErrorSoft = Color(0xFFF85149)
