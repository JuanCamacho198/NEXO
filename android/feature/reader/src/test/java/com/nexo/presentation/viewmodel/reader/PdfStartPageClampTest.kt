package com.nexo.presentation.viewmodel.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1 keeps one page per highlight: PDF.js reports every rect of a multi-page
 * selection in the start page's space, so the ones past its box belong elsewhere.
 */
class PdfStartPageClampTest {
    private val page = PdfPageSize(width = 595f, height = 842f)

    @Test
    fun `rects inside the page box are kept`() {
        val rects = listOf(PdfRect(0f, 0f, 100f, 12f), PdfRect(400f, 820f, 100f, 12f))

        assertEquals(rects, rects.withinStartPage(page))
    }

    @Test
    fun `a rect past the page bottom is dropped`() {
        val inside = PdfRect(10f, 20f, 30f, 12f)
        val nextPage = PdfRect(10f, 842f, 30f, 12f)

        assertEquals(listOf(inside), listOf(inside, nextPage).withinStartPage(page))
    }

    @Test
    fun `a rect past the page right edge is dropped`() {
        val outside = PdfRect(595f, 20f, 30f, 12f)

        assertEquals(emptyList<PdfRect>(), listOf(outside).withinStartPage(page))
    }

    @Test
    fun `a partially overhanging rect is kept in its original numbers`() {
        // It does overlap the start page, so it is that page's line, not the next one's.
        val overhang = PdfRect(590f, 830f, 30f, 30f)

        assertEquals(listOf(overhang), listOf(overhang).withinStartPage(page))
    }

    @Test
    fun `without a page box nothing is dropped`() {
        // An older viewer payload must not lose the whole highlight.
        val rects = listOf(PdfRect(10f, 900f, 30f, 12f))

        assertEquals(rects, rects.withinStartPage(null))
    }
}
