package com.nexo.presentation.viewmodel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterPaginationTest {
    // A 1080×2200 px reading area at density 2.625, 18sp, line height 1.6 and
    // 16dp margins: 42 chars per line × 27 lines × 0.85 fill = 963.9 chars/page.
    private val phone =
        ReadingProgressCalculator.ViewportTypography(
            viewportW = 1080,
            viewportH = 2200,
            fontSizeSp = 18f,
            lineHeight = 1.6f,
            pageMarginsDp = 16f,
            density = 2.625f,
        )

    @Test
    fun `visible text drops head, scripts, styles and tags`() {
        val html =
            """
            <html><head><title>Ignored title</title><style>p { color: red }</style></head>
            <body><h1>Uno</h1><script>var x = 1;</script><p>dos  tres</p></body></html>
            """.trimIndent()

        assertEquals("Uno dos tres".length, visibleTextLength(html))
    }

    @Test
    fun `visible text counts each entity as one character`() {
        assertEquals("a b&c".length, visibleTextLength("<p>a&nbsp;b&amp;c</p>"))
    }

    @Test
    fun `pages remaining at the start of a ten-page chapter is ten`() {
        assertEquals(10, chapterPagesRemaining(chapterTextChars = 9_640, progression = 0.0, viewport = phone))
    }

    @Test
    fun `pages remaining halves at the middle of the chapter`() {
        assertEquals(5, chapterPagesRemaining(chapterTextChars = 9_640, progression = 0.5, viewport = phone))
    }

    @Test
    fun `last page of a chapter reads zero`() {
        assertEquals(0, chapterPagesRemaining(chapterTextChars = 9_640, progression = 0.97, viewport = phone))
        assertEquals(0, chapterPagesRemaining(chapterTextChars = 9_640, progression = 1.0, viewport = phone))
    }

    @Test
    fun `a bigger font leaves more pages`() {
        val bigFont = phone.copy(fontSizeSp = 24f)
        assertTrue(
            chapterPagesRemaining(9_640, 0.0, bigFont) > chapterPagesRemaining(9_640, 0.0, phone),
        )
    }

    @Test
    fun `viewport is read as pixels, not multiplied by density again`() {
        // Doubling the density at the same pixel size must NOT shrink the page
        // count the way treating pixels as dp did: the area is the same, only
        // the text got bigger, so there are more pages, never fewer.
        val denser = phone.copy(density = 5.25f)
        assertTrue(chapterPagesRemaining(9_640, 0.0, denser) > chapterPagesRemaining(9_640, 0.0, phone))
    }

    @Test
    fun `chapter at fraction picks the last start at or before it`() {
        val starts = listOf(0f, 0.2f, 0.55f, 0.8f)

        assertEquals(0, chapterIndexAtFraction(starts, 0.1f))
        assertEquals(1, chapterIndexAtFraction(starts, 0.2f))
        assertEquals(2, chapterIndexAtFraction(starts, 0.79f))
        assertEquals(3, chapterIndexAtFraction(starts, 1f))
    }

    @Test
    fun `chapter at fraction skips chapters whose start is unknown`() {
        assertEquals(2, chapterIndexAtFraction(listOf(0f, null, 0.5f), 0.7f))
        assertNull(chapterIndexAtFraction(listOf(null, null), 0.5f))
    }

    @Test
    fun `chapter title drops toc leaders, dot runs and trailing ellipses`() {
        assertEquals("Capítulo 1", cleanChapterTitle("Capítulo 1 ........ 5"))
        assertEquals("El extranjero", cleanChapterTitle("El extranjero....."))
        assertEquals("Primera parte", cleanChapterTitle("  Primera   parte… "))
        assertEquals("Uno dos", cleanChapterTitle("Uno...dos"))
    }

    @Test
    fun `chapter title that is a file name or blank is no title`() {
        assertNull(cleanChapterTitle("chapter001.xhtml"))
        assertNull(cleanChapterTitle("OEBPS/Text/part0003.html"))
        assertNull(cleanChapterTitle("   "))
        assertNull(cleanChapterTitle("....."))
        assertNull(cleanChapterTitle(null))
    }
}
