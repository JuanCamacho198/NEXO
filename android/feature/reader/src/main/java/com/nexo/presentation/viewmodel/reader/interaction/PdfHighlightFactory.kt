package com.nexo.presentation.viewmodel.reader.interaction

import com.nexo.domain.model.Highlight
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.model.HighlightRect
import com.nexo.presentation.viewmodel.reader.PdfSelectionAnchor
import com.nexo.presentation.viewmodel.reader.withinStartPage
import java.util.UUID

/**
 * The single assembly of a PDF highlight, shared by the colour path
 * ([HighlightManager]) and the annotate/note path ([AnnotationManager]).
 *
 * A second copy of these rules is how a noted row ended up with no geometry:
 * it would persist with a `pdfpage:` cfi and nothing to paint.
 *
 * `page` is the anchor's 1-based page, while `cfiRange` keeps the 0-based
 * `pdfpage:<n>` every existing row and the reader's restore path already use.
 * `locatorJson` stays null — a PDF selection has no Readium locator.
 *
 * v1 scope is one page per highlight, so only the rects that still fall inside
 * the start page's box are kept (see [withinStartPage]).
 */
internal fun pdfHighlight(
    bookId: String,
    anchor: PdfSelectionAnchor,
    text: String,
    color: String = HighlightColor.YELLOW.hex,
    note: String? = null,
    id: String = UUID.randomUUID().toString(),
    updatedAtEpochMillis: Long = System.currentTimeMillis(),
): Highlight =
    Highlight(
        id = id,
        bookId = bookId,
        cfiRange = "pdfpage:${anchor.pageNumber - 1}",
        textContent = text,
        note = note,
        color = color,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = null,
        locatorJson = null,
        page = anchor.pageNumber,
        rects =
            anchor.rects.withinStartPage(anchor.pageSize).map {
                HighlightRect(left = it.x, top = it.y, width = it.width, height = it.height)
            },
    )
