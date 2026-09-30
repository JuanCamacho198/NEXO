package com.nexo

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * PR2 plumbing — Android release string format (spec C1).
 *
 * The release sent to Sentry MUST be `<prefix>@<VERSION_NAME>+<sha12>`
 * (or `+unknown` fallback). Same commit MUST yield the same suffix on every
 * platform — so `git rev-parse --short=12 HEAD` is the single source of
 * truth across the web build (TS), Rust build (build.rs) and Android build
 * (build.gradle.kts).
 *
 * `<prefix>` is `BuildConfig.SENTRY_RELEASE_PREFIX` (`nexo-android`), which
 * `build.gradle.kts` derives from the single Gradle-level `sentryProject` value
 * that also supplies the Sentry plugin's `projectName`. This test consumes that
 * same BuildConfig field instead of a local literal, so it can no longer agree
 * with a stale copy of the slug: renaming the prefix in one place flows to both
 * the production release string and these assertions.
 *
 * This test exercises the exact `format!` shape that
 * `NexoApplication.SentryAndroid.init` composes against `BuildConfig`
 * fields — without touching the Sentry SDK itself.
 */
class ReleaseFormatTest {
    companion object {
        // `android.util.Log` is mocked once for the class so `BuildConfig` /
        // static access to `Log` during `release` string composition does not
        // crash on the JVM test runtime. Same pattern as `DebugDualBreadcrumbTest`.
        @BeforeClass
        @JvmStatic
        fun mockAndroidLog() {
            mockkStatic(Log::class)
            every { Log.println(any(), any(), any()) } returns 0
        }

        @AfterClass
        @JvmStatic
        fun unmockAndroidLog() = unmockkAll()
    }

    @Before
    fun setUp() {
        // Per-test reset in case a previous suite leaked mocks (defensive).
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
    }

    /** Mirrors the composition in `NexoApplication.SentryAndroid.init`. */
    private fun composedRelease(): String = "${BuildConfig.SENTRY_RELEASE_PREFIX}@${BuildConfig.VERSION_NAME}+${BuildConfig.GIT_SHA}"

    @Test
    fun release_startsWithPlatformAndVersion() {
        val release = composedRelease()
        assertTrue(
            "release `\$release` must start with `${BuildConfig.SENTRY_RELEASE_PREFIX}@<version>+`",
            release.startsWith("${BuildConfig.SENTRY_RELEASE_PREFIX}@${BuildConfig.VERSION_NAME}+"),
        )
    }

    @Test
    fun release_shaIsUnknownOrExactly12Chars() {
        val release = composedRelease()
        val sha = release.substringAfterLast('+')
        assertTrue(
            "sha segment `\$sha` must be `unknown` fallback or exactly 12 chars",
            sha == "unknown" || sha.length == 12,
        )
    }

    @Test
    fun release_shaIsLowercaseHex() {
        val release = composedRelease()
        val sha = release.substringAfterLast('+')
        // `unknown` is the documented fallback; everything else must be hex.
        if (sha != "unknown") {
            assertTrue(
                "sha `\$sha` must be lowercase hex",
                sha.all { it.isDigit() || (it in 'a'..'f') },
            )
        }
    }

    @Test
    fun release_isSinglePlatformPlusVersionPlusSha() {
        val release = composedRelease()
        val parts = release.split('@', '+')
        assertEquals(
            "release `\$release` must split into 3 parts on `@` and `+`",
            3,
            parts.size,
        )
        // The prefix is single-sourced from BuildConfig, so this guards the format
        // invariant the split relies on (the prefix must not contain `@` or `+`)
        // instead of a literal snapshot that could go stale.
        assertFalse(
            "prefix `${BuildConfig.SENTRY_RELEASE_PREFIX}` must not contain `@` or `+`",
            BuildConfig.SENTRY_RELEASE_PREFIX.any { it == '@' || it == '+' },
        )
        assertEquals(BuildConfig.SENTRY_RELEASE_PREFIX, parts[0])
        assertEquals(BuildConfig.VERSION_NAME, parts[1])
        assertNotNull(parts[2])
    }
}
