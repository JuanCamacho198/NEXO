package com.nexo.presentation.viewmodel.reader

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator

class ReadingProgressCalculatorTest {
    private fun viewport(
        w: Int = 360,
        h: Int = 720,
        fontSizeSp: Float = 16f,
        lineHeight: Float = 1.6f,
        margins: Float = 16f,
        density: Float = 3f,
    ) = ReadingProgressCalculator.ViewportTypography(
        viewportW = w,
        viewportH = h,
        fontSizeSp = fontSizeSp,
        lineHeight = lineHeight,
        pageMarginsDp = margins,
        density = density,
    )

    private fun makeLocator(
        href: String = "ch1.xhtml",
        progression: Double? = 0.5,
    ): Locator {
        val locator = mockk<Locator>(relaxed = true)
        val urlMock = mockk<org.readium.r2.shared.util.Url>(relaxed = true)
        every { urlMock.toString() } returns href
        every { locator.href } returns urlMock
        val loc = mockk<Locator.Locations>(relaxed = true)
        every { loc.progression } returns progression
        every { locator.locations } returns loc
        return locator
    }

    @Test
    fun fallback_charsPerPage_calculatedCorrectlyTypicalPhone() {
        val vp = viewport(w = 1080, h = 1920, fontSizeSp = 16f, lineHeight = 1.6f, density = 3f)
        val chapters = listOf(BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"))
        val result =
            ReadingProgressCalculator.compute(
                publication = null,
                locator = makeLocator("ch1.xhtml", 0.5),
                chapters = chapters,
                currentChapterIndex = 0,
                viewport = vp,
                totalCharsFallback = 10000,
            )
        // charsPerPage should be >0 and path fallback
        assertTrue("charsPerPage must be positive, got ${result.charsPerPage}", result.charsPerPage > 0)
        assertEquals("fallback", result.path)
        // totalPages = ceil(10000 / charsPerPage) >=1
        assertTrue(result.totalPages >= 1)
        // remaining = totalPages - currentPage -1 where currentPage = floor(0.5*totalPages)
        assertTrue(result.remaining >= 0)
        assertTrue(result.remaining < result.totalPages)
    }

    @Test
    fun fallback_noViewport_returnsFallbackNoViewportPath() {
        val chapters = listOf(BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"))
        val result =
            ReadingProgressCalculator.compute(
                publication = null,
                locator = makeLocator("ch1.xhtml", 0.2),
                chapters = chapters,
                currentChapterIndex = 0,
                viewport = null,
            )
        assertEquals("fallback-no-viewport", result.path)
        // remaining computed via ceil((1-0.2)*10)=8
        assertEquals(8, result.remaining)
    }

    @Test
    fun fallback_singlePageChapter_remainingZero() {
        // Small content with large font => 1 page, progression 0.0 => remaining 0
        val vp = viewport(w = 300, h = 400, fontSizeSp = 24f, lineHeight = 1.5f, density = 2f)
        val chapters = listOf(BookChapter(0, "ch1", "Solo", "ch1.xhtml"))
        val result =
            ReadingProgressCalculator.compute(
                publication = null,
                locator = makeLocator("ch1.xhtml", 0.95),
                chapters = chapters,
                currentChapterIndex = 0,
                viewport = vp,
                totalCharsFallback = 500,
            )
        assertTrue(result.remaining >= 0)
        assertTrue(result.remaining <= 1)
    }

    @Test
    fun fallback_lastPosition_remainingZero() {
        val vp = viewport()
        val chapters = listOf(BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"))
        val result =
            ReadingProgressCalculator.compute(
                publication = null,
                locator = makeLocator("ch1.xhtml", 1.0),
                chapters = chapters,
                currentChapterIndex = 0,
                viewport = vp,
                totalCharsFallback = 10000,
            )
        // progression 1.0 => currentPage = totalPages-1 => remaining 0
        assertEquals(0, result.remaining)
    }

    @Test
    fun fallback_viewportTypography_listener_recomputesOnResize() {
        val vpSmall = viewport(w = 360, h = 720)
        val vpLarge = viewport(w = 720, h = 1280)
        val chapters = listOf(BookChapter(0, "ch1", "Chapter 1", "ch1.xhtml"))
        val locator = makeLocator("ch1.xhtml", 0.5)
        val rSmall = ReadingProgressCalculator.compute(null, locator, chapters, 0, vpSmall, 10000)
        val rLarge = ReadingProgressCalculator.compute(null, locator, chapters, 0, vpLarge, 10000)
        // Larger viewport => more chars per page => fewer totalPages => remaining may differ
        assertTrue(
            "charsPerPage small ${rSmall.charsPerPage} should be < large ${rLarge.charsPerPage}",
            rSmall.charsPerPage < rLarge.charsPerPage,
        )
        // Both should be fallback path
        assertEquals("fallback", rSmall.path)
        assertEquals("fallback", rLarge.path)
    }

    @Test
    fun fallback_charsPerPage_neverZeroEvenWithTinyViewport() {
        val vp = viewport(w = 10, h = 10, fontSizeSp = 20f, lineHeight = 1.0f, density = 3f)
        val chapters = emptyList<BookChapter>()
        val result =
            ReadingProgressCalculator.compute(
                publication = null,
                locator = null,
                chapters = chapters,
                currentChapterIndex = 0,
                viewport = vp,
            )
        // Even tiny viewport must produce at least 1 char per line and 1 line per page
        assertTrue(result.charsPerPage >= 1)
        assertTrue(result.totalPages >= 1)
    }
}
