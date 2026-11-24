package com.nexo.presentation.viewmodel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FootnoteTextTest {
    @Test
    fun `note html becomes plain text with entities decoded`() {
        assertEquals(
            "Camus & el absurdo: “nota”.",
            htmlToPlainText("<p>Camus &amp; el <i>absurdo</i>:&nbsp;“nota”.</p>"),
        )
    }

    @Test
    fun `paragraph breaks are kept as single newlines`() {
        assertEquals("Uno\nDos", htmlToPlainText("<p>Uno</p>\n\n<p>Dos</p>"))
    }

    @Test
    fun `fragment text is read from the element carrying the id`() {
        val html =
            """
            <body><p>Texto del libro.<a href="#n3"><sup>[3]</sup></a></p>
            <aside id="n3" epub:type="footnote"><p>3. Publicado en 1942.</p></aside>
            <p>Otro párrafo.</p></body>
            """.trimIndent()

        assertEquals("3. Publicado en 1942.", extractFragmentText(html, "n3"))
    }

    @Test
    fun `an empty anchor before the note reads the text after it`() {
        val html = """<p class="nota"><a id="nota5"></a>5. El absurdo según Camus.</p><p>Siguiente.</p>"""

        assertEquals("5. El absurdo según Camus.", extractFragmentText(html, "nota5"))
    }

    @Test
    fun `missing id or empty block yields no preview`() {
        assertNull(extractFragmentText("<p id=\"a\">x</p>", "b"))
        assertNull(extractFragmentText("<p id=\"a\"></p>", "a"))
        assertNull(extractFragmentText("<p id=\"a\">x</p>", ""))
    }

    @Test
    fun `long notes are capped on a word boundary`() {
        val long = "palabra ".repeat(200)
        val text = extractFragmentText("<p id=\"n\">$long</p>", "n", maxChars = 50)!!

        assertTrue(text.endsWith("…"))
        assertTrue(text.length <= 51)
        assertTrue(text.removeSuffix("…").endsWith("palabra"))
    }
}
