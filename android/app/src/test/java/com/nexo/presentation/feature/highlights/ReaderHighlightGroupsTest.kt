package com.nexo.presentation.feature.highlights

import com.nexo.domain.model.Highlight
import com.nexo.presentation.feature.highlights.utils.ChapterLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderHighlightGroupsTest {
    private val chapters =
        listOf(
            ChapterLocation(index = 0, title = "I", href = "text/ch1.xhtml"),
            ChapterLocation(index = 1, title = "II", href = "text/ch2.xhtml"),
            ChapterLocation(index = 2, title = "III", href = "text/ch3.xhtml"),
        )

    @Test
    fun groupHighlightsByChapter_ordersByBookPositionNotByInput() {
        val groups =
            groupHighlightsByChapter(
                listOf(
                    highlight("late", "readium:text/ch3.xhtml", progression = 0.80),
                    highlight("early", "readium:text/ch1.xhtml", progression = 0.05),
                    highlight("middle", "readium:text/ch2.xhtml", progression = 0.40),
                ),
                chapters,
            )

        assertEquals(listOf("I", "II", "III"), groups.map { it.chapterTitle })
        assertEquals(listOf("early", "middle", "late"), groups.flatMap { g -> g.highlights.map { it.id } })
    }

    @Test
    fun groupHighlightsByChapter_ordersWithinAChapter() {
        val groups =
            groupHighlightsByChapter(
                listOf(
                    highlight("second", "readium:text/ch1.xhtml", progression = 0.09),
                    highlight("first", "readium:text/ch1.xhtml", progression = 0.02),
                ),
                chapters,
            )

        assertEquals(1, groups.size)
        assertEquals(listOf("first", "second"), groups.single().highlights.map { it.id })
    }

    @Test
    fun groupHighlightsByChapter_usesChapterOrderWhenThereIsNoLocator() {
        val groups =
            groupHighlightsByChapter(
                listOf(
                    highlight("cfi-third", "epubcfi(/6/3!/4/2:0)"),
                    highlight("cfi-first", "epubcfi(/6/1!/4/2:0)"),
                ),
                chapters,
            )

        assertEquals(listOf("I", "III"), groups.map { it.chapterTitle })
    }

    @Test
    fun groupHighlightsByChapter_putsChapterlessHighlightsLast() {
        val groups =
            groupHighlightsByChapter(
                listOf(
                    highlight("unknown", "readium:text/sinopsis.xhtml", progression = 0.01),
                    highlight("known", "readium:text/ch2.xhtml", progression = 0.50),
                ),
                chapters,
            )

        assertEquals("II", groups.first().chapterTitle)
        assertNull(groups.last().chapterTitle)
        assertEquals(listOf("unknown"), groups.last().highlights.map { it.id })
    }

    @Test
    fun groupHighlightsByChapter_emptyInputGivesNoGroups() {
        assertEquals(emptyList<HighlightGroup>(), groupHighlightsByChapter(emptyList(), chapters))
    }

    private fun highlight(
        id: String,
        cfiRange: String,
        progression: Double? = null,
    ) = Highlight(
        id = id,
        bookId = "book",
        cfiRange = cfiRange,
        textContent = id,
        note = null,
        color = "#FACC15",
        updatedAtEpochMillis = 0L,
        deletedAtEpochMillis = null,
        locatorJson = progression?.let { """{"href":"x","locations":{"totalProgression":$it}}""" },
    )
}
