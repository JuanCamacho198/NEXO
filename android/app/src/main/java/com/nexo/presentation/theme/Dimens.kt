package com.nexo.presentation.theme

import androidx.compose.ui.unit.dp

/**
 * Semantic dimensions for NEXO.
 *
 * Use these tokens instead of raw `.dp` literals so spacing stays consistent
 * across screens. The naming follows a 4dp base grid (xs=4, sm=8, md=16, lg=24, xl=32).
 */
object NexoDimens {
    /** Extra-small spacing — tight stack gaps, icon-to-label distance inside chips. */
    val spacingXs = 4.dp

    /** Small spacing — inline icon gaps, dense list rows, padding within chips. */
    val spacingSm = 8.dp

    /** Medium spacing — default screen-edge padding, vertical gaps between list items. */
    val spacingMd = 16.dp

    /** Large spacing — section breaks, card inner padding for content-heavy cards. */
    val spacingLg = 24.dp

    /** Extra-large spacing — top-level section separation, hero spacing. */
    val spacingXl = 32.dp

    /** Size of icons inside the bottom navigation bar (20dp). */
    val iconNavBar = 20.dp

    /** Default corner radius for cards, sheets, and elevated surfaces. */
    val cardCornerRadius = 12.dp

    /** Corner radius for covers and other small edge-adjacent surfaces. */
    val coverCornerRadius = 8.dp

    /** Gap between the leading affordance and the title in a top app bar. */
    val topBarGap = 12.dp

    /** Gap between a top app bar title and its optional subtitle line. */
    val topBarTitleGap = 2.dp

    /** Gap between trailing icon buttons in a top app bar. */
    val topBarActionGap = 8.dp

    /** Diameter of the unread-notification dot on a top app bar. */
    val topBarBadgeSize = 10.dp

    /** Diameter of a trailing icon button's touch target in a top app bar. */
    val topBarActionTarget = 40.dp

    /** Diameter of the user avatar disc in a top app bar. */
    val topBarAvatarSize = 40.dp

    /** Diameter of the brand-row avatar overlaid on the Home hero. */
    val heroAvatarSize = 36.dp

    /** Side length of a trailing icon inside [topBarActionTarget]. */
    val topBarActionIcon = 20.dp

    /** Height of the slim reading-progress bar shown on the reader header. */
    val progressBarHeight = 8.dp
}
