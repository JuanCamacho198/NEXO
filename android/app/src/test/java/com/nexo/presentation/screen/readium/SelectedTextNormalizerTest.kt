package com.nexo.presentation.screen.readium

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedTextNormalizerTest {
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
