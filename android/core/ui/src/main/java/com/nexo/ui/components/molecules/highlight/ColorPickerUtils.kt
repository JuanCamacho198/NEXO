package com.nexo.ui.components.molecules.highlight

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

private const val HUE_EPSILON = 0.001f
private const val FULL_HUE_DEGREES = 360f
private const val HUE_SECTOR_240 = 240f
private const val BYTE_CHANNEL_MAX = 255

fun parseColorHex(hex: String): Color =
    try {
        val sanitized = hex.removePrefix("#")
        Color(("FF$sanitized").toLong(16))
    } catch (_: Exception) {
        Color.Magenta
    }

fun hueFromHex(hex: String): Float {
    val c = parseColorHex(hex)
    val r = c.red
    val g = c.green
    val b = c.blue
    val max = maxOf(r, g, b).coerceAtLeast(HUE_EPSILON)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta < HUE_EPSILON) return 0f
    val h =
        when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
    return if (h < 0f) h + FULL_HUE_DEGREES else h
}

fun spectrumColorAt(
    position: Float,
    hue: Float,
): String {
    val color = hslToColor(hue, saturation = 1f, lightness = position.coerceIn(0f, 1f))
    val r = (color.red * BYTE_CHANNEL_MAX).roundToInt().coerceIn(0, BYTE_CHANNEL_MAX)
    val g = (color.green * BYTE_CHANNEL_MAX).roundToInt().coerceIn(0, BYTE_CHANNEL_MAX)
    val b = (color.blue * BYTE_CHANNEL_MAX).roundToInt().coerceIn(0, BYTE_CHANNEL_MAX)
    return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}"
}

fun hslToColor(
    hue: Float,
    saturation: Float,
    lightness: Float,
): Color {
    val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = lightness - c / 2f
    val (rp, gp, bp) =
        when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < HUE_SECTOR_240 -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
    return Color(rp + m, gp + m, bp + m)
}

fun hslToHex(hue: Float): String {
    val color = hslToColor(hue, saturation = 1f, lightness = 0.5f)
    val r = (color.red * BYTE_CHANNEL_MAX).roundToInt().coerceIn(0, BYTE_CHANNEL_MAX)
    val g = (color.green * BYTE_CHANNEL_MAX).roundToInt().coerceIn(0, BYTE_CHANNEL_MAX)
    val b = (color.blue * BYTE_CHANNEL_MAX).roundToInt().coerceIn(0, BYTE_CHANNEL_MAX)
    return "#${r.toString(16).padStart(2, '0')}${g.toString(16).padStart(2, '0')}${b.toString(16).padStart(2, '0')}"
}
