package com.nexo.presentation.feature.highlights.utils

import androidx.compose.ui.graphics.Color

/**
 * Parses a hex color string to [Color]. Accepts 6-char (RRGGBB) or
 * 8-char (AARRGGBB) strings with or without leading '#'.
 * Falls back to opaque black for unsupported lengths, magenta on parse failure.
 */
fun parseColorHex(hex: String): Color =
    try {
        val sanitized = hex.removePrefix("#")
        val longHex =
            when (sanitized.length) {
                6 -> "FF$sanitized"
                8 -> sanitized
                else -> "FF000000"
            }
        Color(longHex.toLong(16))
    } catch (_: Exception) {
        Color.Magenta
    }
