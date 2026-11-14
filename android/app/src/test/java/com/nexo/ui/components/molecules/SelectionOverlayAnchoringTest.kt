package com.nexo.ui.components.molecules

import android.graphics.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionOverlayAnchoringTest {
    private val density = Density(2f) // 1dp = 2px

    /** Wraps a rect that is ALREADY in the overlay's space (the rule's input). */
    private fun spaceOf(rect: Rect) = SelectionOverlaySpace.of(rect)

    /** 1080x1920 px viewport at this density: header reserve 160px, footer 144px, gap 16px. */
    private fun rect(
        top: Int,
        bottom: Int,
        left: Int = 100,
        right: Int = 300,
    ) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    private fun anchorFor(
        top: Int,
        bottom: Int,
        menuHeightPx: Int,
        left: Int = 100,
        right: Int = 300,
        menuWidthPx: Int = 200,
        viewportWidth: Int = 1080,
        viewportHeight: Int = 1920,
    ) = computeAnchor(
        space = spaceOf(rect(top = top, bottom = bottom, left = left, right = right)),
        menuWidthPx = menuWidthPx,
        menuHeightPx = menuHeightPx,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        gapDp = 8,
        density = density,
    )

    @Test
    fun `computeAnchor prefers above when the full surface fits above the header reserve`() {
        val anchor = anchorFor(top = 300, bottom = 350, menuHeightPx = 100)
        // aboveTop = 300 - 100 - 16 = 184, header reserve 160 => 184 >= 160 places above.
        assertEquals(184, anchor.y)
        // centered: selectionCenter 200, raw 100, maxLeft 880 => x 100
        assertEquals(100, anchor.x)
    }

    @Test
    fun `computeAnchor flips below when near the header`() {
        val anchor = anchorFor(top = 100, bottom = 150, menuHeightPx = 100)
        // belowTop = 150 + 16 = 166; 166 + 100 <= 1920 - 144 fits below.
        assertEquals(166, anchor.y)
    }

    /**
     * The reported defect: a tall panel (a ~180dp definition card) placed with
     * the height of the short menu that preceded it. Every branch below must
     * clear the selection rect — none may cross it.
     */
    @Test
    fun `computeAnchor never overlaps the selection for a tall panel near the top`() {
        val selection = rect(top = 400, bottom = 460)
        val anchor = computeAnchor(spaceOf(selection), menuWidthPx = 560, menuHeightPx = 360, viewportWidth = 1080, viewportHeight = 1920, gapDp = 8, density = density)
        // aboveTop = 400 - 360 - 16 = 24 < 160, so it cannot go above; it must
        // go below the selection instead of growing across it.
        assertEquals(476, anchor.y)
        assertTrue("must clear the selection bottom", anchor.y >= selection.bottom + 16)
    }

    @Test
    fun `computeAnchor keeps a tall panel above when it genuinely fits`() {
        val selection = rect(top = 900, bottom = 960)
        val anchor = computeAnchor(spaceOf(selection), 560, 360, 1080, 1920, 8, density)
        // aboveTop = 900 - 360 - 16 = 524 >= 160 => above, clearing the top.
        assertEquals(524, anchor.y)
        assertTrue("must clear the selection top", anchor.y + 360 <= selection.top - 16)
    }

    @Test
    fun `computeAnchor clamps to right edge`() {
        val anchor = anchorFor(top = 300, bottom = 350, menuHeightPx = 100, left = 900, right = 1000, menuWidthPx = 300)
        // center 950, raw 800, maxLeft 780 => clamp 780
        assertEquals(780, anchor.x)
    }

    @Test
    fun `computeAnchor clamps to left edge`() {
        val anchor = anchorFor(top = 300, bottom = 350, menuHeightPx = 100, left = 0, right = 100, menuWidthPx = 300)
        // center 50, raw -100 => clamp 0
        assertEquals(0, anchor.x)
    }

    /**
     * The old expectation here was "stays above, clamped to 0" — which placed a
     * 1800px surface at y=0 and let it cross the selection. The surface here is
     * taller than the 500px viewport, so no placement can avoid the selection;
     * the rule must still pin it against an edge (never leave it floating at a
     * negative offset) and must start it on the side with room.
     */
    @Test
    fun `computeAnchor pins a surface taller than the viewport instead of crossing it`() {
        val anchor = anchorFor(top = 300, bottom = 350, menuHeightPx = 1800, viewportHeight = 500)
        // No room above (300 < 1800 + 16), so it is pinned to the bottom edge,
        // clamped to 0 because it cannot even start there.
        assertEquals(0, anchor.y)
    }

    /**
     * The guarantee that matters for the reported bug: any surface that FITS in
     * the viewport must clear the selection rect, on whichever side it lands.
     */
    @Test
    fun `computeAnchor clears the selection for every surface that fits in the viewport`() {
        // Sweep selection positions and surface heights that all fit in 1920px.
        for (top in 200..1700 step 100) {
            val selection = rect(top = top, bottom = top + 50)
            for (height in 60..420 step 60) {
                val anchor = computeAnchor(spaceOf(selection), 560, height, 1080, 1920, 8, density)
                val surfaceTop = anchor.y
                val surfaceBottom = anchor.y + height
                val overlaps = surfaceTop < selection.bottom && surfaceBottom > selection.top
                assertTrue(
                    "height=$height top=$top placed at $surfaceTop..$surfaceBottom overlaps " +
                        "[${selection.top}..${selection.bottom}]",
                    !overlaps,
                )
            }
        }
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
