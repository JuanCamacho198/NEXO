package com.nexo.presentation.viewmodel.reader

import android.graphics.Rect
import android.graphics.RectF
import com.nexo.domain.model.Highlight
import org.readium.r2.shared.publication.Locator

/**
 * Sealed representation of the reader's floating-menu state.
 *
 * Replaces the loose boolean flags (`showColorPicker`, `showContextMenu`, etc.)
 * with an explicit, mutually-exclusive state machine:
 * - [None]: no selection is active.
 * - [New]: the user has selected new text and should see the creation menu.
 * - [Existing]: the user tapped an existing highlight and should see the edit menu.
 *
 * NOTE: [New] and [Existing] are **not** `data class`es because they hold
 * `android.graphics.Rect` fields whose `equals()`/`toString()` throw in
 * unit tests unless mocked. Custom `equals`/`hashCode`/`toString` skip
 * `rect` — the rect is transient UI-positioning data that should not
 * influence state-flo w deduplication.
 */
sealed interface ReaderSelectionState {
    data object None : ReaderSelectionState

    class New(
        val rect: Rect,
        val text: String,
        val locator: Locator?,
        val pdfAnchor: PdfSelectionAnchor? = null,
    ) : ReaderSelectionState {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is New) return false
            return text == other.text && locator == other.locator && pdfAnchor == other.pdfAnchor
        }

        override fun hashCode(): Int = 31 * (31 * text.hashCode() + (locator?.hashCode() ?: 0)) + (pdfAnchor?.hashCode() ?: 0)

        override fun toString(): String = "New(rect=$rect, text='$text', locator=$locator, pdfAnchor=$pdfAnchor)"
    }

    class Existing(
        val highlight: Highlight,
        val rect: Rect,
    ) : ReaderSelectionState {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Existing) return false
            return highlight == other.highlight
        }

        override fun hashCode(): Int = highlight.hashCode()

        override fun toString(): String = "Existing(highlight=$highlight, rect=$rect)"
    }
}

/** Converts a Readium viewport-space [RectF] to an Android [Rect] for [ReaderSelectionState]. */
internal fun RectF.toRect(): Rect =
    Rect(
        left.toInt(),
        top.toInt(),
        right.toInt(),
        bottom.toInt(),
    )

/** Page-relative, scale-1 CSS px, top-left origin. Lives here until Phase 3 highlights need it in domain. */
data class PdfRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** The start page's box at scale 1 in CSS px — the frame a [PdfRect] is expressed in. */
data class PdfPageSize(
    val width: Float,
    val height: Float,
)

/**
 * Keeps only the rects that still overlap [page]'s box.
 *
 * PDF.js reports every client rect relative to the page where the selection
 * started, so the part of a selection that continues on the next page arrives
 * with coordinates past the page edge. Rects entirely outside the box belong to
 * another page and are dropped; a partial overhang is kept, because it does
 * overlap the start page and its numbers are already in that page's space.
 *
 * Without a page box nothing can be ruled out, so every rect is kept — an old
 * viewer payload must not silently lose the whole highlight.
 */
fun List<PdfRect>.withinStartPage(page: PdfPageSize?): List<PdfRect> {
    if (page == null) return this
    return filter { rect ->
        rect.x < page.width &&
            rect.x + rect.width > 0f &&
            rect.y < page.height &&
            rect.y + rect.height > 0f
    }
}

/**
 * Where a PDF selection lives, in the format Phase 3 persists.
 *
 * [pageNumber] is **1-based** — the numbering PDF.js reports from `data-page-number` and the
 * one the Readium PDF locator path already yields. `SelectionManager` passes the caller's
 * value through untouched, so callers must hand over a 1-based page. A 0-based index exists
 * only inside the PDF.js bridge dispatch and never reaches this model.
 *
 * [pageSize] is the start page's scale-1 box, or null when the payload omits it. [rects] stay
 * exactly as PDF.js sent them; clamping them to [pageSize] is the consumer's job.
 */
data class PdfSelectionAnchor(
    val pageNumber: Int,
    val rects: List<PdfRect>,
    val pageSize: PdfPageSize? = null,
)
