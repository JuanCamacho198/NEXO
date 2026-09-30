package com.nexo.ui.components.atoms

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the pure rating math of [StarRating] (design D1, REQ-detail-screen-7,
 * SCEN-rating-half): fill resolution and tap-half → half-unit conversion.
 */
class StarRatingTest {
    // ── starFillAt: display math ───────────────────────────────────────

    @Test
    fun starFillAt_nullRating_isAllEmpty() {
        (1..5).forEach { star ->
            assertEquals(StarFill.EMPTY, starFillAt(null, star))
        }
    }

    @Test
    fun starFillAt_rating9_isFourFullPlusHalf() {
        (1..4).forEach { star ->
            assertEquals(StarFill.FULL, starFillAt(9, star))
        }
        assertEquals(StarFill.HALF, starFillAt(9, 5))
    }

    @Test
    fun starFillAt_rating10_isAllFull() {
        (1..5).forEach { star ->
            assertEquals(StarFill.FULL, starFillAt(10, star))
        }
    }

    @Test
    fun starFillAt_rating8_star5IsEmpty() {
        (1..4).forEach { star ->
            assertEquals(StarFill.FULL, starFillAt(8, star))
        }
        assertEquals(StarFill.EMPTY, starFillAt(8, 5))
    }

    @Test
    fun starFillAt_rating1_star1IsHalf() {
        assertEquals(StarFill.HALF, starFillAt(1, 1))
        (2..5).forEach { star ->
            assertEquals(StarFill.EMPTY, starFillAt(1, star))
        }
    }

    @Test
    fun starFillAt_rating0_isAllEmpty() {
        (1..5).forEach { star ->
            assertEquals(StarFill.EMPTY, starFillAt(0, star))
        }
    }

    // ── ratingValueFromTap: tap-half input (SCEN-rating-half) ──────────

    @Test
    fun ratingValueFromTap_leftHalfOfFifthStar_is8() {
        assertEquals(8, ratingValueFromTap(5, isLeftHalf = true))
    }

    @Test
    fun ratingValueFromTap_rightHalfOfFifthStar_is9() {
        assertEquals(9, ratingValueFromTap(5, isLeftHalf = false))
    }

    @Test
    fun ratingValueFromTap_firstStarLeftHalf_is0() {
        assertEquals(0, ratingValueFromTap(1, isLeftHalf = true))
    }

    @Test
    fun ratingValueFromTap_firstStarRightHalf_is1() {
        assertEquals(1, ratingValueFromTap(1, isLeftHalf = false))
    }

    @Test
    fun ratingValueFromTap_thirdStar_is4or5() {
        assertEquals(4, ratingValueFromTap(3, isLeftHalf = true))
        assertEquals(5, ratingValueFromTap(3, isLeftHalf = false))
    }

    // ── ratingDisplayValue: half-units → 0.0..5.0 (D1) ─────────────────

    @Test
    fun ratingDisplayValue_convertsHalfUnitsToDecimal() {
        assertEquals(4.5, ratingDisplayValue(9)!!, 0.0001)
        assertEquals(5.0, ratingDisplayValue(10)!!, 0.0001)
        assertEquals(0.5, ratingDisplayValue(1)!!, 0.0001)
        assertEquals(0.0, ratingDisplayValue(0)!!, 0.0001)
    }

    @Test
    fun ratingDisplayValue_nullRating_staysNull() {
        assertEquals(null, ratingDisplayValue(null))
    }
}
