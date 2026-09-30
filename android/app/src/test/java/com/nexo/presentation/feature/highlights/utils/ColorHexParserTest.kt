package com.nexo.presentation.feature.highlights.utils

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorHexParserTest {
    @Test
    fun `parseColorHex 6-char without hash`() {
        val c = parseColorHex("FF0000")
        assertEquals(Color(0xFFFF0000), c)
    }

    @Test
    fun `parseColorHex 6-char with hash`() {
        val c = parseColorHex("#00FF00")
        assertEquals(Color(0xFF00FF00), c)
    }

    @Test
    fun `parseColorHex 8-char with alpha`() {
        val c = parseColorHex("80FF0000")
        assertEquals(Color(0x80FF0000), c)
    }

    @Test
    fun `parseColorHex 8-char with hash and alpha`() {
        val c = parseColorHex("#8000FF00")
        assertEquals(Color(0x8000FF00), c)
    }

    @Test
    fun `parseColorHex invalid length falls back to black`() {
        val c = parseColorHex("FFF")
        assertEquals(Color(0xFF000000), c)
    }

    @Test
    fun `parseColorHex invalid hex falls back to magenta`() {
        val c = parseColorHex("ZZZZZZ")
        assertEquals(Color.Magenta, c)
    }

    @Test
    fun `parseColorHex empty falls back to black`() {
        val c = parseColorHex("")
        assertEquals(Color(0xFF000000), c)
    }
}
