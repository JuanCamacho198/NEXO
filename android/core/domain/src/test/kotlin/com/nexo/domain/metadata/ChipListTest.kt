package com.nexo.domain.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sanitization helpers for the comma-separated genre/tags metadata fields
 * (REQ-data-model-8). Moved here with [parseChipList] from
 * EditBookMetadataViewModel, which is where they lived while :app owned them.
 */
class ChipListTest {
    @Test
    fun `parseChipList splits comma separated values and drops blanks`() {
        assertEquals(listOf("Fiction", "Adventure"), parseChipList("Fiction, Adventure"))
        assertEquals(emptyList<String>(), parseChipList(null))
        assertEquals(emptyList<String>(), parseChipList("  , ,  "))
        assertEquals(listOf("a", "b"), parseChipList("a,,b"))
    }

    @Test
    fun `sanitizeChipValue strips commas and trims`() {
        assertEquals("Fiction", sanitizeChipValue("  Fic,tion,  "))
        assertNull(sanitizeChipValue(" , , "))
        assertNull(sanitizeChipValue(""))
    }

    @Test
    fun `sanitizeChipList dedupes case-insensitively and caps at max`() {
        val input = listOf("Fantasy", " fantasy ", "FANTASY", "Adventure")
        assertEquals(listOf("Fantasy", "Adventure"), sanitizeChipList(input, max = 5))
        val capped = sanitizeChipList(listOf("a", "b", "c", "d", "e", "f"), max = 5)
        assertEquals(5, capped.size)
    }
}
