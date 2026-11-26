package com.nexo.presentation.screen.pdfjs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.nexo.domain.model.Highlight
import com.nexo.presentation.screen.readium.HIGHLIGHT_ALPHA_HEX
import com.nexo.presentation.screen.resolveHighlightColorHex

/**
 * Paints the saved highlights of the page PDF.js is showing, over the WebView.
 *
 * A [Canvas] on purpose, with no pointer-input modifier: it draws and nothing
 * else. The WebView underneath owns every gesture — scrolling, text selection,
 * its own click handling — and a Compose sibling that hit-tests above an interop
 * view takes them all, so this overlay must stay invisible to touch.
 *
 * Placement comes from [pdfHighlightBoxes]; this only turns boxes into draw calls.
 */
@Composable
fun PdfHighlightOverlay(
    viewport: PdfJsMessage.Viewport?,
    highlights: List<Highlight>,
    density: Float,
    modifier: Modifier = Modifier,
) {
    if (viewport == null) return
    // `clipToBounds` is not decoration: a page scrolled partly out of the viewport
    // has a negative `top` (or one past the bottom), and without clipping the boxes
    // paint straight over the reader chrome and the status bar instead of leaving
    // with the page the WebView already clipped.
    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        for (highlight in pdfHighlightsOnPage(highlights, viewport)) {
            val tint = highlightTint(highlight.color)
            for (box in pdfHighlightBoxes(highlight.rects.orEmpty(), viewport, density)) {
                drawRect(
                    color = tint,
                    topLeft = Offset(box.left, box.top),
                    size = Size(box.width, box.height),
                )
            }
        }
    }
}

/**
 * The colour a highlight is painted with: its stored hex at the shared `0x66`
 * alpha, the same one the EPUB decoration uses.
 *
 * Parsed with [resolveHighlightColorHex], the app's existing hex parser. An
 * unparseable hex falls back to `Color.Yellow`, which is "opaque yellow at the
 * same alpha" — the same fallback the Readium decoration takes.
 */
internal fun highlightTint(hex: String): Color {
    val base = resolveHighlightColorHex(hex) ?: Color.Yellow
    return base.copy(alpha = HIGHLIGHT_ALPHA_HEX / 255f)
}
