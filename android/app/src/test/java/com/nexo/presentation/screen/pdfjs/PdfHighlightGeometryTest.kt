package com.nexo.presentation.screen.pdfjs

import android.graphics.Rect
import androidx.compose.ui.geometry.Rect as ComposeRect
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightRect
import com.nexo.ui.components.molecules.pdfSelectionRectInOverlayPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the arithmetic that places a painted PDF highlight.
 *
 * Three terms, each of which has been a defect waiting to happen: `scale` (the
 * viewer fits the page to the screen width, so it is not 1), `pageRect` (the rects
 * are page-relative, and a page scrolled halfway up starts at a negative `top`),
 * and `density` (CSS px to device px, 2.625 on the `dev_clean` AVD). A dropped or
 * mis-ordered term fails here instead of on a reader's screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfHighlightGeometryTest {
    private val density = 2.625f

    /** A page scrolled above the viewport's top edge, drawn at the page-width fit. */
    private val viewport =
        PdfJsMessage.Viewport(
            page = 4,
            pageRect = PdfJsPageRect(left = 40f, top = -120f, width = 1000f, height = 1414f),
            scale = 1.6f,
        )

    private val rect = HighlightRect(left = 10f, top = 20f, width = 30f, height = 12f)

    private fun highlight(
        id: String = "h1",
        page: Int? = 4,
        rects: List<HighlightRect>? = listOf(rect),
    ) = Highlight(
        id = id,
        bookId = "book",
        cfiRange = "pdfpage:3",
        textContent = "word",
        note = null,
        color = "#FACC15",
        updatedAtEpochMillis = 0L,
        deletedAtEpochMillis = null,
        page = page,
        rects = rects,
    )

    @Test
    fun boxes_applyPageOffsetThenScaleThenDensity() {
        val box = pdfHighlightBoxes(listOf(rect), viewport, density).single()
        // (40 + 10 * 1.6) * 2.625 = 147
        assertEquals(147f, box.left, 0.01f)
        // (-120 + 20 * 1.6) * 2.625 = -231: the page starts above the viewport.
        assertEquals(-231f, box.top, 0.01f)
        // (40 + (10 + 30) * 1.6) * 2.625 = 273
        assertEquals(273f, box.right, 0.01f)
        // (-120 + (20 + 12) * 1.6) * 2.625 = -180.6
        assertEquals(-180.6f, box.bottom, 0.01f)
    }

    @Test
    fun boxes_scaleTerm_scalesTheRectAndNotOnlyThePageOffset() {
        val box = pdfHighlightBoxes(listOf(rect), viewport, density).single()
        // 30 CSS px at scale 1 is 78.75 device px; at the page-width fit it is 126.
        assertEquals(78.75f, rect.width * density, 0.01f)
        assertEquals(rect.width * viewport.scale * density, box.width, 0.01f)
    }

    @Test
    fun boxes_pageOffset_survivesTheConversion() {
        val box = pdfHighlightBoxes(listOf(rect), viewport, density).single()
        // What a page-relative rect alone would give: rect * scale * density.
        assertEquals(40f * density, box.left - rect.left * viewport.scale * density, 0.01f)
        // Negative: the page is scrolled 120 CSS px above the viewport's top edge.
        assertEquals(-120f * density, box.top - rect.top * viewport.scale * density, 0.01f)
    }

    @Test
    fun boxes_agreeWithTheSelectionMenuConversion() {
        // The same word, painted and anchored: both must land on the same pixels.
        // `pdfSelectionRectInOverlayPx` is what SelectionOverlay uses for a PDF
        // selection, so agreement here is what makes a tapped highlight's menu sit
        // on its painted box.
        val bounds = pdfHighlightBoundsCss(listOf(rect), viewport)!!
        val anchored =
            pdfSelectionRectInOverlayPx(
                Rect(
                    kotlin.math.round(bounds.left).toInt(),
                    kotlin.math.round(bounds.top).toInt(),
                    kotlin.math.round(bounds.right).toInt(),
                    kotlin.math.round(bounds.bottom).toInt(),
                ),
                density,
            )
        val painted = pdfHighlightBoxes(listOf(rect), viewport, density).single()
        assertEquals(anchored.left.toFloat(), painted.left, 1f)
        assertEquals(anchored.top.toFloat(), painted.top, 1f)
        assertEquals(anchored.right.toFloat(), painted.right, 1f)
        assertEquals(anchored.bottom.toFloat(), painted.bottom, 1f)
    }

    @Test
    fun boxes_emptyRects_drawNothing() {
        assertEquals(emptyList<ComposeRect>(), pdfHighlightBoxes(emptyList(), viewport, density))
    }

    @Test
    fun highlightsOnPage_matchesTheOneBasedPage() {
        val highlights = listOf(highlight(id = "on4", page = 4), highlight(id = "on3", page = 3), highlight(id = "on5", page = 5))
        assertEquals(listOf("on4"), pdfHighlightsOnPage(highlights, viewport).map { it.id })
    }

    @Test
    fun highlightsOnPage_skipsHighlightsWithoutGeometry() {
        val highlights =
            listOf(
                highlight(id = "noPage", page = null),
                highlight(id = "noRects", rects = null),
                highlight(id = "emptyRects", rects = emptyList()),
                highlight(id = "paintable"),
            )
        assertEquals(listOf("paintable"), pdfHighlightsOnPage(highlights, viewport).map { it.id })
    }

    @Test
    fun highlightAt_hitInsideThePaintedBox() {
        val highlights = listOf(highlight())
        // Centre of the box computed above: x in (147, 273), y in (-231, -180.6).
        assertEquals("h1", pdfHighlightAt(200f, -200f, highlights, viewport, density)?.id)
    }

    @Test
    fun highlightAt_missOutsideThePaintedBox() {
        val highlights = listOf(highlight())
        assertNull(pdfHighlightAt(146f, -200f, highlights, viewport, density))
        assertNull(pdfHighlightAt(200f, -235f, highlights, viewport, density))
        // On the page but on another line of it.
        assertNull(pdfHighlightAt(200f, 300f, highlights, viewport, density))
    }

    @Test
    fun highlightAt_overlapPrefersTheLastPaintedHighlight() {
        val overlapping =
            listOf(
                highlight(id = "below", rects = listOf(HighlightRect(10f, 20f, 300f, 12f))),
                highlight(id = "above", rects = listOf(HighlightRect(10f, 20f, 30f, 12f))),
            )
        assertEquals("above", pdfHighlightAt(200f, -200f, overlapping, viewport, density)?.id)
    }

    @Test
    fun boundsCss_unionsTheRectsAndStaysInCssPixels() {
        val twoLines =
            listOf(
                HighlightRect(left = 10f, top = 20f, width = 30f, height = 12f),
                HighlightRect(left = 10f, top = 34f, width = 18f, height = 12f),
            )
        val bounds = pdfHighlightBoundsCss(twoLines, viewport)!!
        // 40 + 10 * 1.6, and -120 + 20 * 1.6: no density factor anywhere.
        assertEquals(56f, bounds.left, 0.01f)
        assertEquals(-88f, bounds.top, 0.01f)
        assertEquals(40f + 40f * 1.6f, bounds.right, 0.01f)
        assertEquals(-120f + 46f * 1.6f, bounds.bottom, 0.01f)
    }

    @Test
    fun boundsCss_noRects_isNull() {
        assertNull(pdfHighlightBoundsCss(emptyList(), viewport))
    }

    @Test
    fun highlightTint_appliesTheSharedAlpha() {
        assertEquals(0x66 / 255f, highlightTint("#FACC15").alpha, 0.0001f)
    }
}
