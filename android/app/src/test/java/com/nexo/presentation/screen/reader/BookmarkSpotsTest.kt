package com.nexo.presentation.screen.reader

import com.nexo.domain.model.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkSpotsTest {
    // Shape of a real stored locator (escaped slashes included), read from the device DB.
    private fun epub(
        id: String,
        position: Int?,
        progression: Double,
        href: String = "OEBPS\\/Text\\/Section0001.xhtml",
        updatedAt: Long = 0L,
    ): Bookmark {
        val positionJson = position?.let { ""","position":$it""" }.orEmpty()
        return Bookmark(
            id = id,
            bookId = "book",
            cfiLocation = "readium:OEBPS/Text/Section0001.xhtml",
            titleOrSnippet = "I",
            updatedAtEpochMillis = updatedAt,
            deletedAtEpochMillis = null,
            locatorJson = """{"href":"$href","type":"application\/xhtml+xml","locations":{"progression":0.0008$positionJson,"totalProgression":$progression}}""",
        )
    }

    private val here = ReadingSpot(href = "OEBPS/Text/Section0001.xhtml", position = 8, totalProgression = 0.0814)

    @Test
    fun spotOf_readsHrefPositionAndProgressionFromStoredLocator() {
        val spot = BookmarkSpots.spotOf(epub("b", position = 8, progression = 0.08139534883720931))
        assertEquals("OEBPS/Text/Section0001.xhtml", spot.href)
        assertEquals(8, spot.position)
        assertEquals(0.08139534883720931, spot.totalProgression!!, 1e-12)
    }

    @Test
    fun bookmarkAt_samePositionIsThisPage() {
        val bookmark = epub("b", position = 8, progression = 0.0813)
        assertEquals(bookmark, BookmarkSpots.bookmarkAt(listOf(bookmark), here))
    }

    @Test
    fun bookmarkAt_nextPositionIsNotThisPage() {
        assertNull(BookmarkSpots.bookmarkAt(listOf(epub("b", position = 9, progression = 0.0816)), here))
    }

    @Test
    fun bookmarkAt_otherResourceIsNotThisPage() {
        val other = epub("b", position = 8, progression = 0.0813, href = "OEBPS\\/Text\\/Section0002.xhtml")
        assertNull(BookmarkSpots.bookmarkAt(listOf(other), here))
    }

    @Test
    fun bookmarkAt_withoutPositionsFallsBackToProgression() {
        val near = epub("near", position = null, progression = 0.0815)
        val far = epub("far", position = null, progression = 0.09)
        val spot = here.copy(position = null)
        assertEquals(near, BookmarkSpots.bookmarkAt(listOf(far, near), spot))
    }

    @Test
    fun bookmarkAt_unknownSpotMatchesNothing() {
        assertNull(BookmarkSpots.bookmarkAt(listOf(epub("b", position = 8, progression = 0.08)), null))
    }

    @Test
    fun isSameSpot_pdfComparesPagesOnly() {
        assertTrue(BookmarkSpots.isSameSpot(ReadingSpot(pdfPage = 4), ReadingSpot(pdfPage = 4)))
        assertFalse(BookmarkSpots.isSameSpot(ReadingSpot(pdfPage = 4), ReadingSpot(pdfPage = 5)))
        assertFalse(BookmarkSpots.isSameSpot(ReadingSpot(pdfPage = 4), here))
    }

    @Test
    fun inBookOrder_sortsByPositionInTheBookNotBySaveTime() {
        val late = epub("late", position = 40, progression = 0.6, updatedAt = 1L)
        val early = epub("early", position = 3, progression = 0.05, updatedAt = 2L)
        assertEquals(listOf("early", "late"), BookmarkSpots.inBookOrder(listOf(late, early)).map { it.id })
    }

    @Test
    fun progressPercent_roundsWholeBookProgression() {
        assertEquals(8, BookmarkSpots.progressPercent(epub("b", position = 8, progression = 0.0814)))
    }
}
