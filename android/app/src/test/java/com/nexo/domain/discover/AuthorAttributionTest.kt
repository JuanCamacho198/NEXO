package com.nexo.domain.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S1: the author attribution filter rejects every book that is not credited to
 * the rail's author. Surname of the term matches a WHOLE word of one of the
 * book's authors, after case and accent folding. This is the client-side
 * guarantee that a loaded rail never carries criticism or a biography.
 */
class AuthorAttributionTest {
    /** Realistic library credits, one per curated term. */
    private val realisticCredit =
        mapOf(
            "arthur conan doyle" to "Arthur Conan Doyle",
            "edgar allan poe" to "Edgar Allan Poe",
            "oscar wilde" to "Oscar Wilde",
            "jules verne" to "Jules Verne",
            "franz kafka" to "Franz Kafka",
            "charles dickens" to "Charles Dickens",
            "jack london" to "Jack London",
            "h g wells" to "H. G. Wells",
            "mark twain" to "Mark Twain",
            "herman melville" to "Herman Melville",
        )

    @Test
    fun surnameOf_takesTheLastWhitespaceToken() {
        assertEquals("melville", surnameOf("herman melville"))
        assertEquals("doyle", surnameOf("arthur conan doyle"))
        assertEquals("wells", surnameOf("h g wells"))
        assertEquals("poe", surnameOf("  edgar   allan poe  "))
    }

    @Test
    fun surnameOf_isEmptyForBlankInput() {
        assertEquals("", surnameOf(""))
        assertEquals("", surnameOf("   "))
    }

    @Test
    fun foldForMatch_lowercasesAndStripsCombiningAccents() {
        assertEquals("perez", foldForMatch("Pérez"))
        assertEquals("garcia marquez", foldForMatch("García Márquez"))
        assertEquals("melville", foldForMatch("MELVILLE"))
    }

    @Test
    fun matchesAuthor_isTrueForAnExactSurnameMatch() {
        assertTrue(matchesAuthor(listOf("Herman Melville"), "herman melville"))
        assertTrue(matchesAuthor(listOf("Poe"), "edgar allan poe"))
    }

    @Test
    fun matchesAuthor_foldsCaseOnBothSides() {
        assertTrue(matchesAuthor(listOf("HERMAN MELVILLE"), "herman melville"))
        assertTrue(matchesAuthor(listOf("herman melville"), "Herman Melville"))
    }

    @Test
    fun matchesAuthor_matchesAnAccentedAuthorWithAnUnaccentedNeedle() {
        assertTrue(matchesAuthor(listOf("Gabriel García Márquez"), "gabriel garcia marquez"))
        assertTrue(matchesAuthor(listOf("gabriel garcia marquez"), "Gabriel García Márquez"))
    }

    @Test
    fun matchesAuthor_matchesEveryCuratedTermAgainstItsRealisticCredit() {
        for (entry in AUTHOR_ROTATION) {
            val credit = realisticCredit[entry.term]
            assertTrue("missing fixture for ${entry.term}", credit != null)
            assertTrue("term ${entry.term} should match its credit", matchesAuthor(listOf(credit!!), entry.term))
        }
    }

    @Test
    fun matchesAuthor_findsTheSurnameInAMultiAuthorCreditList() {
        assertTrue(
            matchesAuthor(listOf("An Anthology Editor", "Herman Melville"), "herman melville"),
        )
    }

    @Test
    fun matchesAuthor_acceptsSurnameFirstOrderAndStrayPunctuation() {
        assertTrue(matchesAuthor(listOf("Melville, Herman"), "herman melville"))
        assertTrue(matchesAuthor(listOf("Herman Melville."), "herman melville"))
        assertTrue(matchesAuthor(listOf("Herman Melville (1819-1891)"), "herman melville"))
    }

    @Test
    fun matchesAuthor_rejectsWhenTheSurnameIsOnlyASubstringOfALongerWord() {
        assertFalse(matchesAuthor(listOf("Melvilles"), "herman melville"))
        assertFalse(matchesAuthor(listOf("Doyles"), "arthur conan doyle"))
        assertFalse(matchesAuthor(listOf("Poetry Collective"), "edgar allan poe"))
        assertFalse(matchesAuthor(listOf("Poehler"), "edgar allan poe"))
        assertFalse(matchesAuthor(listOf("Wellses"), "h g wells"))
    }

    @Test
    fun matchesAuthor_rejectsAnUnrelatedAuthor() {
        assertFalse(matchesAuthor(listOf("Nathaniel Hawthorne"), "herman melville"))
        // "arthur" is a given name, not the surname; must not match.
        assertFalse(matchesAuthor(listOf("Arthur Miller"), "arthur conan doyle"))
    }

    @Test
    fun matchesAuthor_rejectsABookWithNoAuthors() {
        assertFalse(matchesAuthor(emptyList(), "herman melville"))
    }

    @Test
    fun matchesAuthor_rejectsABlankTerm() {
        assertFalse(matchesAuthor(listOf("Herman Melville"), ""))
        assertFalse(matchesAuthor(listOf("Herman Melville"), "   "))
    }

    @Test
    fun surnamesOfTheWholeRotationAreNonBlank() {
        for (entry in AUTHOR_ROTATION) {
            assertTrue("blank surname for ${entry.slug}", surnameOf(entry.term).isNotEmpty())
        }
    }
}
