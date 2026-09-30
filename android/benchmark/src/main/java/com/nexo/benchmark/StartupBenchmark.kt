package com.nexo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start → Home. `StartupTimingMetric` yields `timeToInitialDisplayMs`, the
 * metric the regression gate thresholds for startup.
 *
 * `CompilationMode.Partial()` (not `None`) is deliberate: it applies the shipped
 * baseline profile (`app/src/main/baseline-prof.txt`, S8), so this journey measures
 * the profile the app actually ships — the spec's "baseline profile consumed and
 * mapped" requirement is exercised here.
 *
 * PRE-CONDITION (device, S10 territory): an authenticated session, otherwise the
 * process cold starts into Auth. Startup timing is first-frame and screen-agnostic,
 * so the metric stays valid, but the "→ Home" leg is only exercised when signed in.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartToHome() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
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
