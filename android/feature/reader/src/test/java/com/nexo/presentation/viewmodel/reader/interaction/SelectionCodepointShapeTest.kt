package com.nexo.presentation.viewmodel.reader.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection log line exists to answer "what SHAPE did the selection
 * arrive in" without shipping the prose (the Sentry snapshot egresses it).
 *
 * Defect B turned on exactly this: the selection arrived as `"de"` — literal
 * ASCII double quotes — and the definability gate refused it before any network
 * call. That is visible from the shape (a leading and trailing `P` with codepoint
 * `0x22`) without ever logging the word.
 */
class SelectionCodepointShapeTest {
    private fun shapeOf(text: String): String = SelectionManager.codepointShapeOf(text)

    @Test
    fun `quoted word reports the surrounding double quotes by codepoint`() {
        val shape = shapeOf("\"de\"")

        assertEquals("PLLP[0x22]", shape)
    }

    @Test
    fun `bare word reports letters only`() {
        assertEquals("LL", shapeOf("de"))
    }

    @Test
    fun `a two word selection keeps the whitespace class`() {
        assertEquals("LLSLLL", shapeOf("el sol"))
    }

    @Test
    fun `accents stay letters and the apostrophe appears by codepoint`() {
        // é is a letter and stays in the class string; the apostrophe is
        // punctuation and must appear by codepoint.
        val shape = shapeOf("l'école")

        assertEquals("LPLLLLL[0x27]", shape)
    }

    @Test
    fun `the shape never contains the selection prose`() {
        val text = "El asilo \"de\" ancianos"

        val shape = shapeOf(text)

        // Only class tokens and bracketed hex codepoints may appear.
        assertTrue(
            "shape must consist solely of class tokens and hex codepoints, was: $shape",
            shape.all { it == 'L' || it == 'S' || it == 'P' || it in '0'..'9' || it in 'A'..'F' || it == '[' || it == ']' || it == ',' || it == 'x' },
        )
        assertTrue("must report the quote codepoint", shape.contains("0x22"))
    }

    @Test
    fun `empty selection has an explicit marker`() {
        assertEquals("<empty>", shapeOf(""))
    }
}
