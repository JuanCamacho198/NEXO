package com.nexo.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX 4 regression: the Sentry initialisation gate must recognise a JVM test
 * runtime so `NexoApplication.onCreate` never initialises the real SDK
 * during unit tests (NEXTPAGE-ANDROID-3). This test itself runs under JUnit, so
 * the detector must report a test runtime and veto initialisation.
 */
class SentryInitGuardTest {
    @Test
    fun `jvm test runtime is detected and Sentry initialisation is vetoed`() {
        assertTrue("A JUnit run must be detected as a JVM test", SentryInitGuard.isJvmTest())
        assertFalse("Sentry must not initialise in unit tests", SentryInitGuard.shouldInitialize())
    }
}
