package com.nexo.presentation.screen.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterLabelsTest {
    private val format = "Cap. %1\$s"

    @Test
    fun romanNumeralsGetThePrefix() {
        assertEquals("Cap. I", chapterLabel("I", format))
        assertEquals("Cap. XIV", chapterLabel("XIV.", format))
        assertEquals("Cap. IV", chapterLabel(" IV ", format))
    }

    @Test
    fun plainNumbersGetThePrefix() {
        assertEquals("Cap. 12", chapterLabel("12", format))
    }

    @Test
    fun realTitlesAreUnchanged() {
        assertEquals("Primera parte", chapterLabel("Primera parte", format))
        assertEquals("Mix", chapterLabel("Mix", format))
        assertEquals("Vi", chapterLabel("Vi", format))
        assertEquals("IIII", chapterLabel("IIII", format))
        assertEquals("", chapterLabel("", format))
    }
}
