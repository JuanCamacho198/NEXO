package com.nexo.ui.components.molecules.highlight

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class ColorPickerUtilsTest {
    @Test
    fun spectrumColorAt_hue0_blackAtZero() {
        // lightness 0 => black regardless of hue
        assertEquals("#000000", spectrumColorAt(0f, 0f).lowercase())
    }

    @Test
    fun spectrumColorAt_hue0_whiteAtOne() {
        assertEquals("#ffffff", spectrumColorAt(1f, 0f).lowercase())
    }

    @Test
    fun hslToColor_hue0_saturatedMid_isRed() {
        val c = hslToColor(0f, 1f, 0.5f)
        // pure red: 255,0,0
        assertEquals(255, (c.red * 255).roundToInt())
        assertEquals(0, (c.green * 255).roundToInt())
        assertEquals(0, (c.blue * 255).roundToInt())
    }

    @Test
    fun hslToColor_hue120_isGreen() {
        val c = hslToColor(120f, 1f, 0.5f)
        assertEquals(0, (c.red * 255).roundToInt())
        assertEquals(255, (c.green * 255).roundToInt())
        assertEquals(0, (c.blue * 255).roundToInt())
    }

    @Test
    fun hslToColor_hue240_isBlue() {
        val c = hslToColor(240f, 1f, 0.5f)
        assertEquals(0, (c.red * 255).roundToInt())
        assertEquals(0, (c.green * 255).roundToInt())
        assertEquals(255, (c.blue * 255).roundToInt())
    }

    @Test
    fun hslToHex_hue360_wrapsToRed() {
        // 360 should be same as 0 (red) modulo
        val hex0 = hslToHex(0f).lowercase()
        val hex360 = hslToHex(360f).lowercase()
        // Our implementation treats 360 as else branch (c,0,x) where x=0 => same as red but may be slightly off due to modulo
        // Accept either red or close; we assert both are valid hex and not null
        assertEquals("#ff0000", hex0)
        // 360 maps to red as well in our logic (hue <60 false... else branch triple c,0,x with x=0 => red)
        assertEquals("#ff0000", hex360)
    }

    @Test
    fun hslToColor_blackAndWhite_extremes() {
        val black = hslToColor(0f, 0f, 0f)
        assertEquals(0, (black.red * 255).roundToInt())
        assertEquals(0, (black.green * 255).roundToInt())
        assertEquals(0, (black.blue * 255).roundToInt())

        val white = hslToColor(0f, 0f, 1f)
        assertEquals(255, (white.red * 255).roundToInt())
        assertEquals(255, (white.green * 255).roundToInt())
        assertEquals(255, (white.blue * 255).roundToInt())
    }

    @Test
    fun hueFromHex_pureColors() {
        // pure red
        assertEquals(0f, hueFromHex("#ff0000"), 1f)
        // pure green ~120
        assertEquals(120f, hueFromHex("#00ff00"), 1f)
        // pure blue ~240
        assertEquals(240f, hueFromHex("#0000ff"), 1f)
    }

    @Test
    fun parseColorHex_validAndInvalid() {
        assertEquals(Color(0xFFFF0000), parseColorHex("#ff0000"))
        assertEquals(Color(0xFF00FF00), parseColorHex("00ff00"))
        // invalid => magenta fallback
        assertEquals(Color.Magenta, parseColorHex("zzzzzz"))
    }

    @Test
    fun spectrumColorAt_mid_saturatedHue() {
        // at 0.5 lightness should give saturated hue color
        val midRed = spectrumColorAt(0.5f, 0f).lowercase()
        assertEquals("#ff0000", midRed)
        val midGreen = spectrumColorAt(0.5f, 120f).lowercase()
        assertEquals("#00ff00", midGreen)
        val midBlue = spectrumColorAt(0.5f, 240f).lowercase()
        assertEquals("#0000ff", midBlue)
    }
}
