package com.nexo.debug

/**
 * Gate for real Sentry SDK initialisation.
 *
 * JVM unit tests (plain JUnit and Robolectric) load the Application declared in
 * the manifest, so `NexoApplication.onCreate` would otherwise initialise the
 * SDK against the developer's non-empty DSN and egress synthetic test failures to
 * the PRODUCTION project (NEXTPAGE-ANDROID-3, `device=robolectric`).
 *
 * Production APKs are never built with a JVM test classpath, so detecting a test
 * runtime here cannot weaken real crash reporting: in a shipped app none of the
 * marker classes exist and [shouldInitialize] returns `true`.
 */
object SentryInitGuard {
    /**
     * Class names that only exist on a JVM test classpath. `org.junit.Test`
     * (JUnit 4) covers Robolectric; the Jupiter marker covers JUnit 5 setups.
     */
    private val JVM_TEST_MARKERS =
        listOf(
            "org.robolectric.Robolectric",
            "org.junit.Test",
            "org.junit.jupiter.api.Test",
        )

    /** True when the current process is a JVM test runner, not a shipped app. */
    fun isJvmTest(): Boolean =
        JVM_TEST_MARKERS.any { marker ->
            runCatching {
                Class.forName(marker, false, SentryInitGuard::class.java.classLoader)
            }.isSuccess
        }

    /** Sentry may initialise only outside a JVM test runtime. */
    fun shouldInitialize(): Boolean = !isJvmTest()
}
