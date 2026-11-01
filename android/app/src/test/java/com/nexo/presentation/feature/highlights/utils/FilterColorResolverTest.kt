package com.nexo.presentation.feature.highlights.utils

import com.nexo.domain.model.HighlightColor
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterColorResolverTest {
    @Test
    fun `resolveFilterHex maps an enum name to its palette hex`() {
        assertEquals(HighlightColor.YELLOW.hex, resolveFilterHex("YELLOW"))
        assertEquals(HighlightColor.GREEN.hex, resolveFilterHex("GREEN"))
        assertEquals(HighlightColor.ORANGE.hex, resolveFilterHex("ORANGE"))
        assertEquals(HighlightColor.BLUE.hex, resolveFilterHex("BLUE"))
        assertEquals(HighlightColor.RED.hex, resolveFilterHex("RED"))
    }

    @Test
    fun `resolveFilterHex is case-insensitive`() {
        assertEquals(HighlightColor.YELLOW.hex, resolveFilterHex("yellow"))
    }

    @Test
    fun `resolveFilterHex passes a hex string through unchanged`() {
        assertEquals("#3B82F6", resolveFilterHex("#3B82F6"))
    }

    @Test
    fun `resolveFilterHex passes an unknown value through unchanged`() {
        assertEquals("MAGENTA", resolveFilterHex("MAGENTA"))
    }
}
