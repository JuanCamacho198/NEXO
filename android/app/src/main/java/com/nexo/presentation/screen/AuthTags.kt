package com.nexo.presentation.screen

/** Stable testTag contract for Maestro E2E. Single source for auth + home anchors. */

object HomeTags {
    const val SCREEN_ROOT = "home_screen_root"
    const val GREETING = "home_greeting"

    /** Full-bleed hero of the first-session Home. */
    const val HERO = "home_hero"

    /** Brand lockup + streak/notification actions overlaid on [HERO]. */
    const val HERO_BRAND_ROW = "home_hero_brand_row"

    /** Hero title, subtitle and amber CTA. */
    const val HERO_MESSAGE = "home_hero_message"

    /** The amber "Explore" CTA pill. */
    const val HERO_CTA = "home_hero_cta"

    /** "Genres you may like" header and chip row. */
    const val GENRES_SECTION = "home_genres_section"

    /** Cloud-sync sign-in banner of the first-session Home. */
    const val SYNC_BANNER = "home_sync_banner"
}

object NavTags {
    const val HOME = "bottom_nav_home"
    const val LIBRARY = "bottom_nav_library"
    const val HIGHLIGHTS = "bottom_nav_highlights"
    const val SETTINGS = "bottom_nav_settings"
}
