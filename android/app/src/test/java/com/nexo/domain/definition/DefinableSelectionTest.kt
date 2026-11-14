package com.nexo.domain.definition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefinableSelectionTest {
    @Test
    fun `a single word is definable`() {
        assertTrue(DefinableSelection.isDefinable("house"))
    }

    @Test
    fun `a phrasal verb is definable`() {
        assertTrue(DefinableSelection.isDefinable("give up"))
    }

    @Test
    fun `an idiom of five words is definable`() {
        assertTrue(DefinableSelection.isDefinable("a piece of cake"))
    }

    @Test
    fun `a six word idiom is refused by the word limit`() {
        assertFalse(DefinableSelection.isDefinable("a piece of very fine cake"))
    }

    @Test
    fun `a sentence with terminating punctuation is refused`() {
        assertFalse(DefinableSelection.isDefinable("She went to the market."))
    }

    @Test
    fun `a short sentence is refused on its punctuation alone`() {
        assertFalse(DefinableSelection.isDefinable("Yes, no"))
    }

    @Test
    fun `a paragraph is refused`() {
        val paragraph =
            "It was the best of times it was the worst of times it was the age of wisdom " +
                "it was the age of foolishness it was the epoch of belief"
        assertFalse(DefinableSelection.isDefinable(paragraph))
    }

    @Test
    fun `digits without a letter are refused`() {
        assertFalse(DefinableSelection.isDefinable("123"))
    }

    @Test
    fun `an ellipsis without a letter is refused`() {
        assertFalse(DefinableSelection.isDefinable("…"))
    }

    @Test
    fun `a hyphenated word is definable`() {
        assertTrue(DefinableSelection.isDefinable("well-known"))
    }

    @Test
    fun `an apostrophe in a contraction does not refuse the word`() {
        assertFalse(DefinableSelection.isDefinable("don't"))
    }

    @Test
    fun `an elided word is refused for its typographic apostrophe`() {
        assertFalse(DefinableSelection.isDefinable("l’école"))
    }

    @Test
    fun `a slash-joined alternative form is definable`() {
        assertTrue(DefinableSelection.isDefinable("gray/grey"))
    }

    @Test
    fun `null is refused without throwing`() {
        assertFalse(DefinableSelection.isDefinable(null))
    }

    @Test
    fun `a blank selection is refused without throwing`() {
        assertFalse(DefinableSelection.isDefinable("   \n\t  "))
    }

    @Test
    fun `an empty string is refused`() {
        assertFalse(DefinableSelection.isDefinable(""))
    }

    @Test
    fun `a lone surrogate is refused without throwing`() {
        assertFalse(DefinableSelection.isDefinable("\uD800"))
    }

    @Test
    fun `surrounding whitespace is trimmed before counting words`() {
        assertTrue(DefinableSelection.isDefinable("   give up   "))
    }

    // ── Surrounding punctuation (Defect B) ──────────────────────────────
    //
    // The drag covers the marks around a word (`El asilo "de" ancianos`), so the
    // selection arrives as `"de"`. The quote is not part of the word; the marks
    // at the two ends are one general shape, and these pin the general trim.

    @Test
    fun `a word wrapped in ASCII double quotes is definable`() {
        assertTrue(DefinableSelection.isDefinable("\"de\""))
    }

    @Test
    fun `a word with a trailing period is definable`() {
        assertTrue(DefinableSelection.isDefinable("efectismos."))
    }

    @Test
    fun `a word with a trailing comma is definable`() {
        assertTrue(DefinableSelection.isDefinable("muerte,"))
    }

    @Test
    fun `a word followed by an ellipsis is definable`() {
        assertTrue(DefinableSelection.isDefinable("de…"))
    }

    @Test
    fun `a word wrapped in parentheses is definable`() {
        assertTrue(DefinableSelection.isDefinable("(de)"))
    }

    @Test
    fun `quotes AND a trailing period together are both trimmed`() {
        // `"efectismos."` — the exact measured shape: a quote at each end and a
        // full stop the quotes enclose. Trimming only quotes would leave
        // `efectismos.` and still be refused by the full stop.
        assertTrue(DefinableSelection.isDefinable("\"efectismos.\""))
    }

    @Test
    fun `a selection of only punctuation is still refused`() {
        // Trimming must not manufacture a word out of marks: `""` and `...` have
        // no letter, so rule 1 refuses them exactly as it refused `…` before.
        assertFalse(DefinableSelection.isDefinable("\"\""))
        assertFalse(DefinableSelection.isDefinable("..."))
        assertFalse(DefinableSelection.isDefinable("(,;)"))
    }

    @Test
    fun `a normal word is untouched by the trim`() {
        assertEquals("house", DefinableSelection.trimSurroundingPunctuation("house"))
        assertEquals("house", DefinableSelection.trimSurroundingPunctuation("  house  "))
    }

    @Test
    fun `the trim keeps word-internal marks`() {
        // The weak marks the gate allows inside a word survive: only the ENDS
        // are stripped, so the hyphen and the right single quote stay.
        assertEquals("well-known", DefinableSelection.trimSurroundingPunctuation("well-known"))
        assertEquals("l\u2019\u00e9cole", DefinableSelection.trimSurroundingPunctuation("l\u2019\u00e9cole"))
        assertEquals("gray/grey", DefinableSelection.trimSurroundingPunctuation("gray/grey"))
    }

    @Test
    fun `the trim strips both ends of a quoted phrase but keeps its words`() {
        assertEquals("a piece of cake", DefinableSelection.trimSurroundingPunctuation("\"a piece of cake\""))
    }

    @Test
    fun `a quoted sentence is still refused on its internal punctuation`() {
        // The trim must not turn a passage into a headword: the full stop and
        // the comma sit BETWEEN the letters and stay, so the gate still refuses.
        assertFalse(DefinableSelection.isDefinable("\"She went to the market.\""))
        assertFalse(DefinableSelection.isDefinable("(Yes, no)"))
    }
}
