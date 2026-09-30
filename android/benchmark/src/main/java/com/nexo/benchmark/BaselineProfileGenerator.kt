package com.nexo.benchmark

import android.os.Build
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline profile GENERATOR (SDD android-stack-modernization S11).
 *
 * This is the producer half of the `androidx.baselineprofile` wiring: `:app` applies the
 * consumer plugin and maps `:app:generateReleaseBaselineProfile` onto this module, so the
 * profile shipped at `app/src/main/baseline-prof.txt` is generated from the SAME real
 * journeys the S9 macrobenchmark classes measure — not hand-written.
 *
 * Journey coverage mirrors the four macrobenchmark classes deliberately, because the spec
 * requires generated profiles to map to real benchmark journeys:
 *  - [coldStartToHomeProfile]    ↔ `StartupBenchmark`      (cold start → Home)
 *  - [libraryScrollProfile]      ↔ `LibraryScrollBenchmark` (Library grid scroll)
 *  - [discoverFlingProfile]      ↔ `DiscoverFlingBenchmark` (Discover fling)
 *  - [openReaderProfile]         ↔ `ReaderOpenBenchmark`    (Library → book → Read)
 *
 * DEVICE-ONLY: collection requires a connected device/emulator (rooted, or API ≥ 33 —
 * the plugin documents that same bar for `useConnectedDevices`). It therefore runs ONLY in
 * the dispatch-only `android-baseline-profile` CI job; `automaticGenerationDuringBuild` is
 * off in `:app`, so no normal build ever reaches this class.
 *
 * Every block passes `includeInStartupProfile` explicitly rather than relying on the
 * library default: the cold-start block feeds the startup profile, and the navigation
 * blocks must NOT (they would push non-startup classes into it). `maxIterations` is left at
 * the library default (a cap, not a fixed cost — collection stops once the profile
 * stabilizes).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun requireProfileCapableDevice() {
        // Assume, not fail: an older device cannot collect a profile at all, and that is an
        // infeasible environment — not a broken journey. (The CI emulator is API 34.)
        assumeTrue(
            "Baseline profile collection needs a rooted device or API >= 33",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        )
    }

    /** Cold start → Home, feeding the startup profile. */
    @Test
    fun coldStartToHomeProfile() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
            profileBlock = {
                startActivityAndWait()
                device.waitForIdle()
            },
        )

    /** Library grid: sustained scroll over the heaviest recycled-layout surface. */
    @Test
    fun libraryScrollProfile() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false,
            profileBlock = {
                startActivityAndWait()
                navigateTo(BottomTab.LIBRARY)
                scrollContent()
            },
        )

    /** Discover: fling over the most image-heavy surface. */
    @Test
    fun discoverFlingProfile() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false,
            profileBlock = {
                startActivityAndWait()
                navigateTo(BottomTab.DISCOVER)
                flingContent()
            },
        )

    /**
     * Library → first book → Read: the Readium-hosted reader, which the design flags as the
     * hardest journey.
     *
     * Unlike `ReaderOpenBenchmark` (which `Assume`s the seeded library away — correct there,
     * because a skipped measurement is honest), this block must NOT throw mid-collection: an
     * `AssumptionViolatedException` inside a profile block aborts the run and can discard the
     * rules already collected. A missing book therefore degrades this leg to a no-op, exactly
     * like the sibling journey's skip, while the other three journeys still produce a profile.
     * The conditional is on library CONTENT, never on a fabricated metric.
     */
    @Test
    fun openReaderProfile() =
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = false,
            profileBlock = {
                startActivityAndWait()
                navigateTo(BottomTab.LIBRARY)

                val cover = scrollable().findObject(By.clickable(true))
                if (cover != null) {
                    cover.click()
                    device.waitForIdle()

                    awaitAnyText(READ_CTA_LABELS)?.let { readCta ->
                        readCta.click()
                        device.waitForIdle()
                    }
                }
            },
        )

    companion object {
        /** BookDetail's primary read action across both shipped locales. */
        private val READ_CTA_LABELS =
            listOf("Continue Reading", "Start Reading", "Continuar leyendo", "Empezar a leer")
    }
}
