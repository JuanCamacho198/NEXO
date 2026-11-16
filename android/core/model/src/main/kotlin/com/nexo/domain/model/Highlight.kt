package com.nexo.domain.model

import androidx.compose.runtime.Immutable

/**
 * Color options for highlights matching the Pencil kixeV design palette.
 *
 * Migration from v1 colors (PINK → ORANGE, PURPLE → RED, shifted YELLOW/GREEN/BLUE):
 * | Constant | Old Hex | New Hex |
 * |----------|---------|---------|
 * | YELLOW   | #FDE047 | #FACC15 |
 * | GREEN    | #86EFAC | #4ADE80 |
 * | BLUE     | #93C5FD | #3B82F6 |
 * | ORANGE   | —       | #F97316 |
 * | RED      | —       | #EF4444 |
 */
enum class HighlightColor(
    val hex: String,
) {
    YELLOW("#FACC15"),
    GREEN("#4ADE80"),
    BLUE("#3B82F6"),
    ORANGE("#F97316"),
    RED("#EF4444"),
    ;

    companion object {
        /**
         * Returns the 5 default highlight colour hex strings for the palette.
         * Used as fallback when [ReaderSettings.customHighlightColors] is null.
         */
        fun defaultHexList(): List<String> = entries.map { it.hex }

        /**
         * Looks up a highlight colour by hex string.
         *
         * - Exact match (case-insensitive) when the hex belongs to a current entry.
         * - Nearest-match via RGB Euclidean distance for legacy hex values (e.g.
         *   old PINK #F9A8D4 → ORANGE, old PURPLE #D8B4FE → BLUE).
         * - Returns [YELLOW] as the default fallback for unknown or unparseable
         *   hex values.
         * - Returns `null` only for a truly unparseable input (non-hex string).
         */
        fun fromHex(hex: String): HighlightColor? {
            val sanitized = hex.removePrefix("#").trim()
            if (!sanitized.matches(Regex("^[0-9A-Fa-f]{6}$"))) return null
            val upper = sanitized.uppercase()

            // 1) Exact match
            entries
                .find { it.hex.removePrefix("#").equals(upper, ignoreCase = true) }
                ?.let { return it }

            // 2) Nearest-match by RGB Euclidean distance
            val targetR = upper.substring(0, 2).toInt(16)
            val targetG = upper.substring(2, 4).toInt(16)
            val targetB = upper.substring(4, 6).toInt(16)

            return entries.minByOrNull { entry ->
                val eHex = entry.hex.removePrefix("#")
                val eR = eHex.substring(0, 2).toInt(16)
                val eG = eHex.substring(2, 4).toInt(16)
                val eB = eHex.substring(4, 6).toInt(16)
                val dr = targetR - eR
                val dg = targetG - eG
                val db = targetB - eB
                dr * dr + dg * dg + db * db
            } ?: YELLOW
        }
    }
}

/**
 * A highlight or annotation within a book.
 *
 * @property tag Optional user-defined tag for categorising highlights.
 */
@Immutable
data class Highlight(
    val id: String,
    val bookId: String,
    val cfiRange: String,
    val textContent: String,
    val note: String?,
    val color: String,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
    val tag: String? = null,
    val locatorJson: String? = null,
    val type: String? = null,
)
