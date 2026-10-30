package com.nexo.presentation.screen

import androidx.compose.ui.unit.dp
import com.nexo.presentation.feature.home.CollapsedHeroHeight
import com.nexo.presentation.feature.home.HeroHeight
import com.nexo.presentation.feature.home.HeroPhotoHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Home hero's geometry.
 *
 * Both variants are fed by the same `HeroFrame`: the empty library gets
 * [HeroHeight], a populated one gets [CollapsedHeroHeight]. Compose rendering
 * tests belong in androidTest/ with createComposeRule; what is verifiable on
 * the JVM is the relationship between the three design constants.
 *
 * [CollapsedHeroHeight] is 289dp — the height of the `.pen` `hero_section` on
 * the populated Home (`JlX0K`). An earlier revision of this test asserted 150dp,
 * a value invented before the design existed; that figure never matched the
 * mock and was replaced once the `.pen` became the source of truth.
 */
class HomeHeroTest {
    @Test
    fun collapsedHero_isShorterThanFullHero() {
        assertTrue(
            "A populated Home must not spend the full hero's height",
            CollapsedHeroHeight < HeroHeight,
        )
    }

    @Test
    fun collapsedHero_matchesTheDesignedPopulatedHeroHeight() {
        // The `.pen` `hero_section` of `JlX0K` (populated Home) is 289dp.
        assertEquals(
            "The collapsed hero height is a design decision, not a derived value",
            289.dp,
            CollapsedHeroHeight,
        )
    }

    @Test
    fun photo_tallerThanBothVisibleHeroes() {
        // Both heroes show a 420dp photo and clip it, so the shorter hero is a
        // crop of the same image rather than a rescaled one. If the photo were
        // sized to the visible frame, the cabin would change scale between
        // states.
        assertTrue(
            "The photo must cover the full hero: $HeroPhotoHeight vs $HeroHeight",
            HeroPhotoHeight >= HeroHeight,
        )
        assertTrue(
            "The photo must exceed the collapsed hero, otherwise nothing is cropped",
            HeroPhotoHeight > CollapsedHeroHeight,
        )
    }
}
