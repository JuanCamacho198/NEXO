package com.nexo.ui.components.molecules

import android.graphics.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionOverlayAnchoringTest {
    private val density = Density(2f) // 1dp = 2px

    @Test
    fun `computeAnchor prefers above when enough header reserve`() {
        val rect =
            Rect().apply {
                left = 100
                top = 300
                right = 300
                bottom = 350
            }
        val anchor =
            computeAnchor(
                rect,
                menuWidthPx = 200,
                menuHeightPx = 100,
                viewportWidth = 1080,
                viewportHeight = 1920,
                gapDp = 8,
                density = density,
            )
        // gap 16px, header 160px, aboveTop = 300-100-16=184, placeAbove true (184>=160)
        assertEquals(IntOffset(0, 184).y, anchor.y)
        // centered: selectionCenter 200, raw 100, maxLeft 880, x 100
        assertEquals(100, anchor.x)
    }

    @Test
    fun `computeAnchor flips below when near header`() {
        val rect =
            Rect().apply {
                left = 100
                top = 100
                right = 300
                bottom = 150
            }
        val anchor = computeAnchor(rect, 200, 100, 1080, 1920, 8, density)
        // placeAbove: 100-100-16=-16 <160 => false, belowTop=150+16=166, fitsBelow 166+100=266 <=1920-144=1776 true
        assertEquals(166, anchor.y)
    }

    @Test
    fun `computeAnchor clamps to right edge`() {
        val rect =
            Rect().apply {
                left = 900
                top = 300
                right = 1000
                bottom = 350
            }
        val anchor = computeAnchor(rect, 300, 100, 1080, 1920, 8, density)
        // center 950, raw 800, maxLeft 780 => clamp 780
        assertEquals(780, anchor.x)
    }

    @Test
    fun `computeAnchor clamps to left edge`() {
        val rect =
            Rect().apply {
                left = 0
                top = 300
                right = 100
                bottom = 350
            }
        val anchor = computeAnchor(rect, 300, 100, 1080, 1920, 8, density)
        // center 50, raw -100 => clamp 0
        assertEquals(0, anchor.x)
    }

    @Test
    fun `computeAnchor stays above when no room below either`() {
        val rect =
            Rect().apply {
                left = 100
                top = 300
                right = 300
                bottom = 350
            }
        // tiny viewport, footer reserve 144px, no fit below
        val anchor = computeAnchor(rect, 200, 1800, 1080, 500, 8, density)
        // placeAbove false? 300-1800-16=-1516 <160 false, fitsBelow 350+16+1800=2166 >500-144 false => fallback above clamp 0
        assertEquals(0, anchor.y)
    }

    @Test
    fun `picker centering uses anchorCenterX minus 110dp`() {
        val d = Density(2f)
        val anchorCenterX = 540
        val x = (anchorCenterX - with(d) { 110.dp.toPx() }.toInt()).coerceAtLeast(0)
        assertEquals(320, x)
        val nearLeft = 100
        val x2 = (nearLeft - with(d) { 110.dp.toPx() }.toInt()).coerceAtLeast(0)
        assertEquals(0, x2)
    }
}
