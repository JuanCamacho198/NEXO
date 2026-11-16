@file:OptIn(ExperimentalReadiumApi::class)

package com.nexo.presentation.screen.readium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.nexo.debug.DebugLog
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi

/** Horizontal edge width, as a fraction of the viewport, one-sided. */
internal const val TAP_EDGE_PERCENT = 0.15

/** Tolerance for boundary comparisons, so an exact-edge pixel is not lost to float error. */
private const val BOUNDARY_EPSILON = 1e-6

private const val TAG = "ReadiumTapZones"

/**
 * Pure decision for the center-tap listener: given the viewport width and the
 * tap x coordinate, tells whether the tap lands in the central band (i.e. it is
 * NOT inside either lateral navigation edge).
 *
 * Kept free of Readium types so it can be unit-tested on the JVM.
 */
internal fun isCenterTap(
    x: Float,
    viewportWidth: Int,
    edgePercent: Double = TAP_EDGE_PERCENT,
): Boolean {
    if (viewportWidth <= 0) return false
    val progression = (x / viewportWidth.toFloat()).toDouble()
    return progression >= edgePercent - BOUNDARY_EPSILON &&
        progression <= 1.0 - edgePercent + BOUNDARY_EPSILON
}

/**
 * Registers the reader's tap-zone input listeners on the EPUB navigator.
 *
 * Registration order is load-bearing: the internal `CompositeInputListener`
 * dispatches in registration order and stops at the first listener returning
 * `true`. The edge-navigation adapter is registered first so it can claim
 * lateral taps; the center-tap listener is registered last so it never steals
 * a tap the navigator (or the text-selection machinery) already handled.
 *
 * @param navigatorFragment current Readium fragment (nullable while loading)
 * @param onToggleChrome invoked when a central tap should show/hide the chrome
 */
@Composable
fun rememberTapZonesBridge(
    navigatorFragment: EpubNavigatorFragment?,
    onToggleChrome: () -> Unit,
) {
    val currentOnToggleChrome by rememberUpdatedState(onToggleChrome)

    DisposableEffect(navigatorFragment) {
        val overflowable = navigatorFragment as? OverflowableNavigator
        if (overflowable == null) {
            DebugLog.warn(TAG, "Fragment is not an OverflowableNavigator — tap zones disabled")
            return@DisposableEffect onDispose {}
        }

        val edgeAdapter =
            DirectionalNavigationAdapter(
                overflowable,
                horizontalEdgeThresholdPercent = TAP_EDGE_PERCENT,
            )

        val centerTapListener =
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    val width = navigatorFragment.view?.width ?: 0
                    if (!isCenterTap(event.point.x, width)) return false
                    currentOnToggleChrome()
                    return true
                }
            }

        overflowable.addInputListener(edgeAdapter)
        overflowable.addInputListener(centerTapListener)
        DebugLog.info(TAG, "Tap-zone listeners registered (edge=$TAP_EDGE_PERCENT)")

        onDispose {
            overflowable.removeInputListener(edgeAdapter)
            overflowable.removeInputListener(centerTapListener)
            DebugLog.info(TAG, "Tap-zone listeners removed")
        }
    }
}
