package com.nexo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Discover scroll/fling → `frameDurationCpuMs`.
 *
 * Warm start (process resident) so the measured block is the fling itself, not
 * process bring-up; the Discover list is the app's most image-heavy surface, which
 * is why it carries the frame-timing threshold.
 */
@RunWith(AndroidJUnit4::class)
class DiscoverFlingBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun discoverFling() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            navigateTo(BottomTab.DISCOVER)
            flingContent()
        }

    companion object {
        @AfterClass
        @JvmStatic
        fun exportMetrics() {
            BenchmarkMetricsExporter.export()
        }
    }
}
