package com.nexo.presentation.viewmodel.reader.interaction

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Readium's reflowable engine can hand back a selection rect whose `bottom` is
 * ABOVE its `top` — measured on a real device as
 * `RectF(left=314.0, top=466.25, right=576.8281, bottom=306.25)` for a selection
 * that crossed a CSS multi-column boundary.
 *
 * [SelectionManager.toNormalizedRect] is the single boundary where Readium's
 * coordinates become an Android [android.graphics.Rect], so it owns the
 * normalization. These tests pin the contract that matters:
 *
 * - an inverted rect is ordered, not passed through;
 * - a well-formed rect is untouched;
 * - the result always clears the anchoring rule's overlap guarantee, because
 *   `computeAnchor` reads `top` to place above and `bottom` to place below.
 */
@RunWith(RobolectricTestRunner::class)
class SelectionManagerRectNormalizationTest {
    @Test
    fun `inverted rect is ordered so bottom is below top`() {
        val inverted = RectF(314.0f, 466.25f, 576.8281f, 306.25f)

        val rect = SelectionManager.toNormalizedRect(inverted)

        assertEquals(314, rect.left)
        assertEquals(306, rect.top)
        assertEquals(577, rect.right)
        assertEquals(466, rect.bottom)
    }

    @Test
    fun `well formed rect is unchanged`() {
        val wellFormed = RectF(100f, 200f, 400f, 280f)

        val rect = SelectionManager.toNormalizedRect(wellFormed)

        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(400, rect.right)
        assertEquals(280, rect.bottom)
    }

    @Test
    fun `zero height rect keeps its single y`() {
        val flat = RectF(50f, 300f, 250f, 300f)

        val rect = SelectionManager.toNormalizedRect(flat)

        assertEquals(300, rect.top)
        assertEquals(300, rect.bottom)
    }

    @Test
    fun `normalized rect never has a negative height`() {
        // The regression that placed the panel over the word: an inverted rect
        // used to reach the viewport before anything checked its ordering.
        val inverted = RectF(0f, 900f, 200f, 100f)

        val rect = SelectionManager.toNormalizedRect(inverted)

        assertEquals(800, rect.height())
        assertEquals(200, rect.width())
    }
}
