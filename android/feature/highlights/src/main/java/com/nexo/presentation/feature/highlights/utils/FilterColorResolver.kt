package com.nexo.presentation.feature.highlights.utils

import com.nexo.domain.model.HighlightColor

/**
 * Resolves a sheet filter value to a displayable hex colour.
 *
 * The highlights sheet keeps its filter state as [HighlightColor] enum *names*
 * (e.g. `"YELLOW"`), while highlight rows store hex strings (e.g. `"#FACC15"`).
 * That dualism is deliberate and documented in `HighlightsSheet.kt` — this
 * function exists only to render the swatch, it does not unify the two.
 *
 * An enum name is mapped to its palette hex. Anything else is passed through
 * unchanged, so a value that is already a hex string still renders correctly.
 */
fun resolveFilterHex(filterValue: String): String =
    HighlightColor.entries
        .firstOrNull { it.name.equals(filterValue, ignoreCase = true) }
        ?.hex
        ?: filterValue
