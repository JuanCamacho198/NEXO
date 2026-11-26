package com.nexo.presentation.screen.pdfjs

import androidx.compose.ui.geometry.Rect
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightRect

/**
 * Every conversion in this file is the same three terms, in this order:
 *
 * ```
 * overlayPx = (pageRect.left + rect.left * scale) * density
 * ```
 *
 * - `rect` is page-relative at **scale 1** — the persisted format, not a viewport
 *   coordinate. `rect * scale` turns it back into the page's own CSS px.
 * - `pageRect` is where the page starts **inside the WebView viewport**, in CSS px.
 *   It is not zero and not constant: scrolling moves it (a page scrolled halfway
 *   up starts at a negative `top`) and it is never transmitted with a highlight.
 *   Adding it is what makes the overlay follow the page instead of the screen.
 * - `density` converts CSS px to device px. `viewer.html` pins
 *   `<meta name="viewport" content="width=device-width, initial-scale=1">`, so one
 *   CSS px is `DisplayMetrics.density` device px in the PDF.js WebView — the same
 *   factor `pdfSelectionRectInOverlayPx` applies to a selection.
 *
 * `scale` alone is not enough (page-width made it a non-1 fit factor) and
 * `density` alone is not enough (it would be off by `scale`). Both, plus the page
 * offset, are what a painted highlight needs. `PdfHighlightGeometryTest` pins the
 * arithmetic so a dropped term fails there instead of on a reader's screen.
 */
fun pdfHighlightBoxes(
    rects: List<HighlightRect>,
    viewport: PdfJsMessage.Viewport,
    density: Float,
): List<Rect> {
    val page = viewport.pageRect
    return rects.map { rect ->
        Rect(
            left = (page.left + rect.left * viewport.scale) * density,
            top = (page.top + rect.top * viewport.scale) * density,
            right = (page.left + (rect.left + rect.width) * viewport.scale) * density,
            bottom = (page.top + (rect.top + rect.height) * viewport.scale) * density,
        )
    }
}

/**
 * The highlights that belong on the page [viewport] describes.
 *
 * [Highlight.page] is **1-based**, exactly like `viewport.page`; a PDF row written
 * before geometry existed has no page and no rects and is skipped, which is the
 * honest answer — there is nothing to place it with.
 */
fun pdfHighlightsOnPage(
    highlights: List<Highlight>,
    viewport: PdfJsMessage.Viewport,
): List<Highlight> = highlights.filter { it.page == viewport.page && !it.rects.isNullOrEmpty() }

/**
 * The highlight under a tap at ([x], [y]) in Compose px, or null when the tap
 * missed every painted box.
 *
 * Last-wins: when two highlights overlap, the one painted on top is the one the
 * reader sees and therefore the one they meant.
 */
fun pdfHighlightAt(
    x: Float,
    y: Float,
    highlights: List<Highlight>,
    viewport: PdfJsMessage.Viewport,
    density: Float,
): Highlight? =
    pdfHighlightsOnPage(highlights, viewport).lastOrNull { highlight ->
        pdfHighlightBoxes(highlight.rects.orEmpty(), viewport, density).any { box ->
            x >= box.left && x < box.right && y >= box.top && y < box.bottom
        }
    }

/**
 * The highlight's bounding box in the space a PDF.js selection reports: viewport
 * **CSS px**, no density factor.
 *
 * That is deliberate. The menu anchors through the same conversion a selection
 * uses (`pdfSelectionRectInOverlayPx`), so handing it CSS px makes a tapped
 * highlight land exactly where the painted box is, with one density
 * multiplication in the codebase instead of two.
 */
fun pdfHighlightBoundsCss(
    rects: List<HighlightRect>,
    viewport: PdfJsMessage.Viewport,
): PdfJsBounds? {
    if (rects.isEmpty()) return null
    val page = viewport.pageRect
    val left = rects.minOf { it.left }
    val top = rects.minOf { it.top }
    val right = rects.maxOf { it.left + it.width }
    val bottom = rects.maxOf { it.top + it.height }
    return PdfJsBounds(
        left = page.left + left * viewport.scale,
        top = page.top + top * viewport.scale,
        right = page.left + right * viewport.scale,
        bottom = page.top + bottom * viewport.scale,
    )
}
