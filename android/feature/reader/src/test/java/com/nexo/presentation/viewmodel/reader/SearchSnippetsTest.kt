package com.nexo.presentation.viewmodel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSnippetsTest {
    @Test
    fun `short context is kept whole`() {
        assertEquals("tal y como él", snippetBefore(" tal y como él"))
        assertEquals(" lo va contando", snippetAfter(" lo va contando  "))
    }

    @Test
    fun `long before context keeps the end and starts on a word`() {
        val before = "La historia de un francés en Argelia, tal y como él mismo la va contando"
        val snippet = snippetBefore(before, max = 20)

        assertTrue(snippet.startsWith("…"))
        assertTrue(before.endsWith(snippet.removePrefix("…")))
        assertFalse(snippet.removePrefix("…").startsWith(" "))
    }

    @Test
    fun `long after context keeps the start and ends on a word`() {
        val after = " desde el entierro de su madre un fin de semana hasta los cinco tiros"
        val snippet = snippetAfter(after, max = 20)

        assertTrue(snippet.endsWith("…"))
        assertTrue(after.startsWith(snippet.removeSuffix("…")))
        assertEquals(" desde el entierro…", snippet)
    }

    @Test
    fun `newlines and runs of spaces collapse to one space`() {
        assertEquals("a b c", snippetBefore("a\n\n b   c"))
    }
}
