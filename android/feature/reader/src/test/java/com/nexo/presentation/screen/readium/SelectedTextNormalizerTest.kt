package com.nexo.presentation.screen.readium

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedTextNormalizerTest {
    @Test
    fun decodeJsStringResult_quotedWord_dropsTheJsonQuotes() {
        // Measured: selecting "aspecto" logged a 9-char text whose ends were 0x22.
        assertEquals("aspecto", decodeJsStringResult("\"aspecto\""))
    }

    @Test
    fun decodeJsStringResult_escapes_areDecoded() {
        assertEquals("dijo \"sí\"\nluego\\", decodeJsStringResult("\"dijo \\\"sí\\\"\\nluego\\\\\""))
        assertEquals("día", decodeJsStringResult("\"d\\u00eda\""))
    }

    @Test
    fun decodeJsStringResult_emptyScriptResult_isEmpty() {
        assertEquals("", decodeJsStringResult("\"\""))
    }

    @Test
    fun decodeJsStringResult_notAJsonString_isLeftAlone() {
        assertEquals("null", decodeJsStringResult("null"))
        assertEquals("palabra", decodeJsStringResult("palabra"))
    }

    @Test
    fun normalizeSelectedText_leadingSpace_trimsIt() {
        assertEquals("palabra", normalizeSelectedText(" palabra"))
    }

    @Test
    fun normalizeSelectedText_trailingSpace_trimsIt() {
        assertEquals("palabra", normalizeSelectedText("palabra "))
    }

    @Test
    fun normalizeSelectedText_surroundingSpaces_trimsThem() {
        assertEquals("palabra", normalizeSelectedText("  palabra  "))
    }

    @Test
    fun normalizeSelectedText_internalSpace_isPreserved() {
        assertEquals("dos palabras", normalizeSelectedText("dos palabras"))
    }

    @Test
    fun normalizeSelectedText_internalSpaces_arePreservedVerbatim() {
        assertEquals("dos  palabras", normalizeSelectedText("dos  palabras"))
    }

    @Test
    fun normalizeSelectedText_empty_returnsEmpty() {
        assertEquals("", normalizeSelectedText(""))
    }

    @Test
    fun normalizeSelectedText_whitespaceOnly_returnsEmpty() {
        assertEquals("", normalizeSelectedText("   \n\t "))
    }

    @Test
    fun normalizeSelectedText_newlinesAtEdges_trimsThem() {
        assertEquals("linea uno\nlinea dos", normalizeSelectedText("\nlinea uno\nlinea dos\n"))
    }
}
