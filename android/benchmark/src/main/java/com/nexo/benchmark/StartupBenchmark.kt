package com.nexo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start → whatever the app shows. `StartupTimingMetric` yields
 * `timeToInitialDisplayMs`, the metric the regression gate thresholds for startup.
 *
 * `CompilationMode.Partial()` (not `None`) is deliberate: it applies the shipped
 * baseline profile (`app/src/main/baseline-prof.txt`, S8), so this journey measures
 * the profile the app actually ships — the spec's "baseline profile consumed and
 * mapped" requirement is exercised here.
 *
 * NO SESSION REQUIRED — and none is needed. A cold start is the app's own launch
 * path: unauthenticated it resolves to Auth, authenticated to Home, and either way
 * `timeToInitialDisplayMs` is first-frame and screen-agnostic. The check below only
 * documents which leg ran; it never gates the measurement.
 *
 * `FrameTimingMetric` is deliberately NOT attached here. It was, and it made this
 * journey fail with `Unable to confirm activity launch completion []` on the CI
 * emulator: that message is thrown by `MacrobenchmarkScope.amStartAndWait` after
 * 100 x 100ms polls of `getFrameStats()`, i.e. when `dumpsys gfxinfo <pkg>
 * framestats` returned no `---PROFILEDATA---` block at all (`FrameStatsResult.parse`
 * splits on that literal marker and yields an EMPTY list — the `[]` is that list).
 * It is a frame-stats collection failure, NOT an auth failure. Attaching frame
 * timing to a startup journey adds that whole dependency to buy a metric nothing
 * thresholds here (`frameDurationCpuMs` is owned by the scroll/fling journeys).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartToHome() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.waitForIdle()
        }

    companion object {
        @AfterClass
        @JvmStatic
        fun exportMetrics() {
            BenchmarkMetricsExporter.export()
        }
    }
}
