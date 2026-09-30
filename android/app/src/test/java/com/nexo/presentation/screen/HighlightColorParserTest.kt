package com.nexo.presentation.screen

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [resolveHighlightColorHex].
 */
class HighlightColorParserTest {
    @Test
    fun sixDigitHex_parsesAsOpaque() {
        val color = resolveHighlightColorHex("#facc15")!!
        assertEquals(0xFFFACC15.toInt(), color.toArgb())
    }

    @Test
    fun sixDigitHex_withoutHash_parses() {
        val color = resolveHighlightColorHex("facc15")!!
        assertEquals(0xFFFACC15.toInt(), color.toArgb())
    }

    @Test
    fun eightDigitArgb_parsesWithAlpha() {
        val color = resolveHighlightColorHex("#80ff0000")!!
        assertEquals(0x80FF0000.toInt(), color.toArgb())
    }

    @Test
    fun invalidHex_returnsNull() {
        assertNull(resolveHighlightColorHex("banana"))
        assertNull(resolveHighlightColorHex(""))
        assertNull(resolveHighlightColorHex("#12345"))
        assertNull(resolveHighlightColorHex("#123456789"))
    }

    @Test
    fun transparentAlpha_returnsNull() {
        assertNull(resolveHighlightColorHex("#00000000"))
        assertNull(resolveHighlightColorHex("#00facc15"))
    }
}
