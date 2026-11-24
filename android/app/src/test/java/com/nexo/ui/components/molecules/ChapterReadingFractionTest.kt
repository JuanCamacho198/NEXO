package com.nexo.ui.components.molecules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterReadingFractionTest {
    @Test
    fun chapterReadingFraction_midChapter() {
        // Chapter 1 spans 20%..40% of the book; the reader is at 30%.
        assertEquals(0.5f, chapterReadingFraction(30f, listOf(0f, 0.2f, 0.4f), current = 1)!!, 0.001f)
    }

    @Test
    fun chapterReadingFraction_lastChapterEndsAtBookEnd() {
        assertEquals(0.5f, chapterReadingFraction(70f, listOf(0f, 0.4f), current = 1)!!, 0.001f)
    }

    @Test
    fun chapterReadingFraction_skipsEntriesSharingTheSameStart() {
        // A part and its first sub-chapter both start at 10%; the chapter ends at 50%.
        assertEquals(0.25f, chapterReadingFraction(20f, listOf(0.1f, 0.1f, 0.5f), current = 0)!!, 0.001f)
    }

    @Test
    fun chapterReadingFraction_unknownStartGivesNull() {
        assertNull(chapterReadingFraction(30f, listOf(0f, null, 0.4f), current = 1))
        assertNull(chapterReadingFraction(30f, emptyList(), current = 0))
    }

    @Test
    fun chapterReadingFraction_clampsOutsideTheChapter() {
        assertEquals(0f, chapterReadingFraction(5f, listOf(0f, 0.2f, 0.4f), current = 1)!!, 0.001f)
        assertEquals(1f, chapterReadingFraction(90f, listOf(0f, 0.2f, 0.4f), current = 1)!!, 0.001f)
    }
}
