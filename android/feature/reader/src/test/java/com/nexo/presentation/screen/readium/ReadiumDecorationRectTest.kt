package com.nexo.presentation.screen.readium

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [ReadiumOverlaySpace.decorationRect] to the numbers measured on the
 * emulator (Readium 3.2.0, paginated, 128px page inset): tapping the highlight
 * "descontento" reported top 741 / bottom 669, while selecting "aspecto" on the
 * same line reported top 613 / bottom 669.
 */
@RunWith(RobolectricTestRunner::class)
class ReadiumDecorationRectTest {
    @Test
    fun `a tapped highlight lands on the same line as a selection there`() {
        val tapped = RectF(735.6f, 740.8f, 1008.5f, 668.8f)
        val selectionOnSameLine = RectF(545.8f, 612.8f, 715.2f, 668.8f)

        val corrected = ReadiumOverlaySpace.decorationRect(tapped, pageInsetTop = 128)

        assertEquals(selectionOnSameLine.top, corrected.top, 0.01f)
        assertEquals(selectionOnSameLine.bottom, corrected.bottom, 0.01f)
        assertEquals(tapped.left, corrected.left, 0.01f)
        assertEquals(tapped.right, corrected.right, 0.01f)
    }

    @Test
    fun `without a page inset the rect is left alone`() {
        val tapped = RectF(10f, 200f, 90f, 250f)

        val corrected = ReadiumOverlaySpace.decorationRect(tapped, pageInsetTop = 0)

        assertEquals(tapped.top, corrected.top, 0.01f)
        assertEquals(tapped.bottom, corrected.bottom, 0.01f)
    }
}
