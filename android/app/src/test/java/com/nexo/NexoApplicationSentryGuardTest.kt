package com.nexo

import com.nexo.debug.SentryInitGuard
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.sentry.Sentry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FIX 4 regression: a Robolectric run instantiates the manifest Application,
 * whose `onCreate` used to call `SentryAndroid.init` against the developer's
 * real DSN and egress synthetic test failures to the production project
 * (NEXTPAGE-ANDROID-3, `device=robolectric`).
 *
 * The manifest disables Sentry's auto-init provider (`io.sentry.auto-init=false`),
 * so `Sentry.isEnabled()` can only become true through `NexoApplication`.
 * This test pins that it stays disabled.
 * WS2c slice 6: ambient Hilt test app; canary pins the guard + disabled SDK.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class NexoApplicationSentryGuardTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Test
    fun `creating the Application does not initialize the real Sentry SDK`() {
        assertFalse(SentryInitGuard.shouldInitialize())

        assertFalse(
            "Sentry must stay disabled while running unit tests",
            Sentry.isEnabled(),
        )
    }
}
