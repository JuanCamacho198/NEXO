package com.nexo.ui.components.atoms

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the snackbar's motion contract.
 *
 * The composable itself needs an instrumented test to render, so what is
 * pinned here is the pure spec layer: durations stay inside the short
 * "feedback, not spectacle" band, the enter/exit curves are the in-house
 * decelerate/accelerate pair, and motion collapses to zero when the platform
 * asks for reduced motion.
 */
class NexoSnackbarMotionTest {
    /** Upper bound for a snackbar transition — longer reads as a blocking dialog. */
    private val maxFeedbackMs = 250

    @Test
    fun `enter duration is inside the short feedback band`() {
        assertTrue(
            "enter must be > 0 and <= $maxFeedbackMs ms",
            NexoSnackbarMotion.ENTER_DURATION_MS in 1..maxFeedbackMs,
        )
    }

    @Test
    fun `exit duration is inside the short feedback band`() {
        assertTrue(
            "exit must be > 0 and <= $maxFeedbackMs ms",
            NexoSnackbarMotion.EXIT_DURATION_MS in 1..maxFeedbackMs,
        )
    }

    @Test
    fun `exit is not slower than enter`() {
        // An arriving surface may take its moment to register; a leaving one
        // must clear out quickly — a lingering dismissal reads as a stuck UI.
        assertTrue(
            "exit (${NexoSnackbarMotion.EXIT_DURATION_MS}ms) must be <= " +
                "enter (${NexoSnackbarMotion.ENTER_DURATION_MS}ms)",
            NexoSnackbarMotion.EXIT_DURATION_MS <= NexoSnackbarMotion.ENTER_DURATION_MS,
        )
    }

    @Test
    fun `enter spec uses the decelerating curve`() {
        val spec = NexoSnackbarMotion.enterSpec<Float>()
        val expected =
            tween<Float>(
                durationMillis = NexoSnackbarMotion.ENTER_DURATION_MS,
                easing = NexoSnackbarMotion.ENTER_EASING,
            )
        assertSameAnimationSpec(expected, spec)
    }

    @Test
    fun `exit spec uses the accelerating curve`() {
        val spec = NexoSnackbarMotion.exitSpec<Float>()
        val expected =
            tween<Float>(
                durationMillis = NexoSnackbarMotion.EXIT_DURATION_MS,
                easing = NexoSnackbarMotion.EXIT_EASING,
            )
        assertSameAnimationSpec(expected, spec)
    }

    @Test
    fun `enter slides the full height of the surface from below`() {
        // The host measures the content, so a full-height offset is the only
        // value that guarantees the snackbar starts fully off-screen.
        val height = 120
        assertEquals(IntOffset(0, height), NexoSnackbarMotion.enterOffset(height))
    }

    @Test
    fun `enter offset collapses to zero when motion is reduced`() {
        assertEquals(
            IntOffset.Zero,
            NexoSnackbarMotion.enterOffset(height = 120, motionScale = 0f),
        )
    }

    @Test
    fun `enter scale starts slightly below one`() {
        val start = NexoSnackbarMotion.ENTER_START_SCALE
        assertTrue("start scale must be below 1.0", start < 1f)
        assertTrue("start scale must stay close to 1.0, subtle not bouncy", start >= 0.9f)
    }

    @Test
    fun `scale is disabled when motion is reduced`() {
        assertEquals(1f, NexoSnackbarMotion.enterStartScale(motionScale = 0f))
        assertEquals(
            NexoSnackbarMotion.ENTER_START_SCALE,
            NexoSnackbarMotion.enterStartScale(motionScale = 1f),
        )
    }

    @Test
    fun `fractional motion scale is honoured, not treated as a boolean`() {
        // Android's animator duration scale can be 0.5; a snackbar at half
        // scale should travel half the distance, not the full height.
        assertEquals(60, NexoSnackbarMotion.enterOffset(height = 120, motionScale = 0.5f).y)
    }

    @Test
    fun `exit offset mirrors the enter offset`() {
        // The surface must leave downward by the same distance it arrived from,
        // or it appears to jump on dismissal.
        assertEquals(
            NexoSnackbarMotion.enterOffset(height = 120, motionScale = 1f),
            NexoSnackbarMotion.exitOffset(height = 120, motionScale = 1f),
        )
        assertEquals(
            NexoSnackbarMotion.enterOffset(height = 120, motionScale = 0f),
            NexoSnackbarMotion.exitOffset(height = 120, motionScale = 0f),
        )
    }

    private fun assertSameAnimationSpec(
        expected: FiniteAnimationSpec<Float>,
        actual: FiniteAnimationSpec<Float>,
    ) {
        assertEquals("animation spec (duration + easing) must match", expected, actual)
    }
}
