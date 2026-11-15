package com.nexo.presentation.feature.highlights.utils

import com.nexo.domain.model.HighlightColor

/**
 * Returns `true` when a highlight stored with [highlightColor] should be shown
 * under [filterName], the sheet's selected filter.
 *
 * The sheet keeps its filter as a [HighlightColor] enum *name* (`"YELLOW"`)
 * while rows store a hex string (`"#FACC15"`). That dualism is deliberate and
 * documented in `HighlightsSheet.kt`; this function is the bridge between the
 * two, it does not unify them.
 *
 * A `null` [filterName] is the "All" chip and matches everything.
 *
 * Comparison goes through [HighlightColor.fromHex] rather than string equality
 * so that legacy palette values (old PINK `#F9A8D4`, old PURPLE `#D8B4FE`)
 * resolve to their nearest current colour and stay reachable from that colour's
 * filter. A highlight whose colour is not parseable is only reachable through
 * "All".
 */
fun matchesHighlightFilter(
    highlightColor: String,
    filterName: String?,
): Boolean {
    if (filterName == null) return true
    val filterHex =
        HighlightColor.entries
            .firstOrNull { it.name.equals(filterName, ignoreCase = true) }
            ?.hex
            ?: return false
    return HighlightColor.fromHex(highlightColor)?.hex == filterHex
}
