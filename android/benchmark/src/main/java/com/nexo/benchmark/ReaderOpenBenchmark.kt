package com.nexo.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.AfterClass
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reader open (Library → first book → Read) → `frameDurationCpuMs`.
 *
 * The design flags this as the hardest journey: the Reader is a Readium-hosted
 * fragment, not a plain Compose destination. It is therefore guarded by EXPLICIT
 * assumptions instead of environment-dependent text matching:
 *  - the library must hold at least one book (a clickable node inside the grid) —
 *    without a seeded library there is nothing to open, so the journey SKIPS;
 *  - the BookDetail read CTA must be present — if the detail screen renders without
 *    it the journey also SKIPS rather than reporting a fake metric.
 * A missing authenticated session still FAILS (via [navigateTo]) for every journey
 * alike, because that is a broken benchmark environment, not an infeasible journey.
 *
 * Seeding the library belongs to the device workstream (S10/S11).
 */
@RunWith(AndroidJUnit4::class)
class ReaderOpenBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openReaderFirstBook() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = ITERATIONS,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            navigateTo(BottomTab.LIBRARY)

            val cover = firstLibraryBook()
            Assume.assumeTrue("Reader journey needs a seeded library book", cover != null)
            cover?.click()
            device.waitForIdle()

            val readCta = awaitAnyText(READ_CTA_LABELS)
            Assume.assumeTrue("BookDetail did not expose a read action", readCta != null)
            readCta?.click()
            // The Readium fragment renders its first page after this settles.
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), NAV_TIMEOUT_MS)
            device.waitForIdle()
        }

    /**
     * First clickable node inside the grid's scrollable container — locale-free.
     * The library toolbar sits OUTSIDE the scrollable, so the first clickable
     * descendant is the first book card (covers carry the title as
     * contentDescription, so matching text/desc would be title-dependent).
     */
    private fun MacrobenchmarkScope.firstLibraryBook(): UiObject2? = scrollable().findObject(By.clickable(true))

    companion object {
        /** BookDetail's primary read action across both shipped locales. */
        private val READ_CTA_LABELS =
            listOf("Continue Reading", "Start Reading", "Continuar leyendo", "Empezar a leer")

        @AfterClass
        @JvmStatic
        fun exportMetrics() {
            BenchmarkMetricsExporter.export()
        }
    }
}
