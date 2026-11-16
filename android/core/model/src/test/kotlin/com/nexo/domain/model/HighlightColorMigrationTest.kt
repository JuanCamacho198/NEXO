package com.nexo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [HighlightColor] enum migration from Pencil v1 colors to kixeV design colors.
 *
 * Migration map:
 *   YELLOW: #FDE047 → #FACC15
 *   GREEN:  #86EFAC → #4ADE80
 *   PINK:   #F9A8D4 → removed (nearest = ORANGE #F97316)
 *   BLUE:   #93C5FD → #3B82F6
 *   PURPLE: #D8B4FE → removed (nearest = BLUE #3B82F6)
 *   ORANGE: (new)     #F97316
 *   RED:    (new)     #EF4444
 */
class HighlightColorMigrationTest {
    // ── New enum values ─────────────────────────────────────────────

    @Test
    fun `YELLOW hex matches kixeV design`() {
        assertEquals("#FACC15", HighlightColor.YELLOW.hex)
    }

    @Test
    fun `GREEN hex matches kixeV design`() {
        assertEquals("#4ADE80", HighlightColor.GREEN.hex)
    }

    @Test
    fun `BLUE hex matches kixeV design`() {
        assertEquals("#3B82F6", HighlightColor.BLUE.hex)
    }

    @Test
    fun `ORANGE hex matches kixeV design`() {
        assertEquals("#F97316", HighlightColor.ORANGE.hex)
    }

    @Test
    fun `RED hex matches kixeV design`() {
        assertEquals("#EF4444", HighlightColor.RED.hex)
    }

    @Test
    fun `enum has exactly 5 entries`() {
        assertEquals(5, HighlightColor.entries.size)
    }

    // ── Exact new-color matches ─────────────────────────────────────

    @Test
    fun `fromHex exact match YELLOW`() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.fromHex("#FACC15"))
    }

    @Test
    fun `fromHex exact match GREEN`() {
        assertEquals(HighlightColor.GREEN, HighlightColor.fromHex("#4ADE80"))
    }

    @Test
    fun `fromHex exact match BLUE`() {
        assertEquals(HighlightColor.BLUE, HighlightColor.fromHex("#3B82F6"))
    }

    @Test
    fun `fromHex exact match ORANGE`() {
        assertEquals(HighlightColor.ORANGE, HighlightColor.fromHex("#F97316"))
    }

    @Test
    fun `fromHex exact match RED`() {
        assertEquals(HighlightColor.RED, HighlightColor.fromHex("#EF4444"))
    }

    // ── Old hex → nearest new color (RGB Euclidean distance) ────────

    @Test
    fun `fromHex old YELLOW FDE047 migrates to YELLOW`() {
        assertEquals(HighlightColor.YELLOW, HighlightColor.fromHex("#FDE047"))
    }

    @Test
    fun `fromHex old GREEN 86EFAC migrates to GREEN`() {
        assertEquals(HighlightColor.GREEN, HighlightColor.fromHex("#86EFAC"))
    }

    @Test
    fun `fromHex old BLUE 93C5FD migrates to BLUE`() {
        assertEquals(HighlightColor.BLUE, HighlightColor.fromHex("#93C5FD"))
    }

    @Test
    fun `fromHex old PINK F9A8D4 migrates to RED (nearest by RGB distance)`() {
        // PINK #F9A8D4 — nearest new color is RED #EF4444 (d²=30836 vs ORANGE d²=38909)
        assertEquals(HighlightColor.RED, HighlightColor.fromHex("#F9A8D4"))
    }

    @Test
    fun `fromHex old PURPLE D8B4FE migrates to BLUE (nearest by RGB distance)`() {
        // PURPLE #D8B4FE — nearest new color is BLUE #3B82F6
        assertEquals(HighlightColor.BLUE, HighlightColor.fromHex("#D8B4FE"))
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    fun `fromHex black hex maps to nearest RED`() {
        // #000000 — nearest color by RGB distance is RED (#EF4444, d²=66369)
        assertEquals(HighlightColor.RED, HighlightColor.fromHex("#000000"))
    }

    @Test
    fun `fromHex lowercase hex works case-insensitively`() {
        assertEquals(HighlightColor.RED, HighlightColor.fromHex("#ef4444"))
    }

    @Test
    fun `fromHex hex without hash prefix works`() {
        assertEquals(HighlightColor.GREEN, HighlightColor.fromHex("4ADE80"))
    }

    @Test
    fun `fromHex invalid string returns null`() {
        assertEquals(null, HighlightColor.fromHex("not-a-color"))
    }
}
