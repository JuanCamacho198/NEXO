package com.nexo.ui.components.atoms

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * Motion contract for [NexoSnackbar], kept separate from the composable so it
 * can be asserted in a plain JVM unit test.
 *
 * A snackbar is confirmation, not an announcement: the whole transition fits
 * inside 250ms, which is the same band the reader chrome uses. Curves follow
 * the house style seen in `NexoDownloadOverlay` and `BookmarkRibbonOverlay` —
 * decelerate on the way in, accelerate on the way out.
 */
internal object NexoSnackbarMotion {
    /** Time for the surface to arrive. */
    const val ENTER_DURATION_MS: Int = 200

    /** Time for the surface to leave. Never longer than [ENTER_DURATION_MS]. */
    const val EXIT_DURATION_MS: Int = 180

    /** Subtle scale-up on arrival — enough to register, not enough to bounce. */
    const val ENTER_START_SCALE: Float = 0.96f

    /**
     * Decelerate-in: fast start, gentle settle. Mirrors `EaseOutCubic`, which
     * the download and import overlays already use for entering surfaces.
     */
    val ENTER_EASING: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    /**
     * Accelerate-out: gentle start, quick exit. Mirrors `EaseInCubic`, the
     * counterpart curve on those same overlays.
     */
    val EXIT_EASING: Easing = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)

    fun <T> enterSpec(): FiniteAnimationSpec<T> = tween(durationMillis = ENTER_DURATION_MS, easing = ENTER_EASING)

    fun <T> exitSpec(): FiniteAnimationSpec<T> = tween(durationMillis = EXIT_DURATION_MS, easing = EXIT_EASING)

    /**
     * Vertical travel for the enter transition: the full height of the surface,
     * so it starts completely off-screen.
     *
     * The composable passes the surface's own measured height, but the value is
     * only ever a *lower bound* on the real travel — overshooting just parks the
     * surface further off-screen, which is free. That is what keeps this free of
     * a measure-feedback loop.
     *
     * @param motionScale The platform's animation scale (see
     *   [MotionDurationScale]), where `0f` means the user has asked for reduced
     *   motion. Non-zero values scale the travel proportionally, so a
     *   system-wide `0.5` produces half the slide rather than a full one.
     */
    fun enterOffset(
        height: Int,
        motionScale: Float = 1f,
    ): IntOffset {
        if (motionScale <= 0f) return IntOffset.Zero
        return IntOffset(0, (height * motionScale).roundToInt())
    }

    /**
     * Vertical travel for the exit transition: the surface slides back down by
     * its own height, mirroring [enterOffset].
     */
    fun exitOffset(
        height: Int,
        motionScale: Float = 1f,
    ): IntOffset = enterOffset(height, motionScale)

    /**
     * Scale the surface starts from on arrival. Collapses to `1f` — no scale at
     * all — when the user has asked for reduced motion.
     */
    fun enterStartScale(motionScale: Float = 1f): Float {
        if (motionScale <= 0f) return 1f
        // Lerp from 1.0 toward ENTER_START_SCALE by the platform scale so a
        // half-scale system setting halves the effect instead of ignoring it.
        return 1f + (ENTER_START_SCALE - 1f) * motionScale
    }
}
