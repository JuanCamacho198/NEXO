package com.nexo.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Current window width in dp, as the surfaces see it.
 *
 * Read from `LocalConfiguration` rather than
 * `WindowMetricsCalculator.computeCurrentWindowMetrics` on purpose: the Activity
 * object is not reachable from a feature module, and Compose already recomposes
 * on a configuration change, so the value stays live across rotation, window
 * resize and fold/unfold without a listener.
 */
@Composable
fun rememberWindowWidthDp(): Int {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(configuration.screenWidthDp, density) {
        // `screenWidthDp` is already in dp; the density remember key is here so a
        // font-scale/density change recomputes rather than serving a stale value.
        configuration.screenWidthDp
    }
}

/**
 * Column count for a book grid, from the shared breakpoints in [AdaptiveLayout].
 *
 * At compact width this always returns 2 — the count the phone layout shipped
 * with — so no phone layout changes.
 */
@Composable
fun rememberGridColumnCount(): Int {
    val widthDp = rememberWindowWidthDp()
    return remember(widthDp) { AdaptiveLayout.gridColumnCount(widthDp) }
}

/**
 * Reader text-column cap in dp, or null when the viewport is narrow enough that
 * the column should keep filling it.
 */
@Composable
fun rememberReaderMaxWidth(): androidx.compose.ui.unit.Dp? {
    val widthDp = rememberWindowWidthDp()
    return remember(widthDp) {
        val capped = AdaptiveLayout.readerMaxWidthDp(widthDp)
        if (capped >= widthDp) null else capped.dp
    }
}
