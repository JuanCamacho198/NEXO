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
 * Library grid scroll → `frameDurationCpuMs`.
 *
 * Scroll (not fling) is used here on purpose: the two journeys then cover both
 * gesture classes — fling velocity in Discover, sustained scroll frames in the
 * Library grid, which is the heaviest recycled-layout surface.
 *
 * SKIPS without an authenticated session: the bottom bar is composed only
 * `if (isAuthenticated)` (NexoNavHost.kt:270), so there is no Library tab to reach.
 * The skip is honest — "not applicable on this device" — rather than measuring the
 * Auth form under a name that promises the Library grid. See
 * [assumeAuthenticatedSession].
 */
@RunWith(AndroidJUnit4::class)
class LibraryScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun libraryScroll() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            assumeAuthenticatedSession()
            navigateTo(BottomTab.LIBRARY)
            scrollContent()
        }

    companion object {
        @AfterClass
        @JvmStatic
        fun exportMetrics() {
            BenchmarkMetricsExporter.export()
        }
    }
}
