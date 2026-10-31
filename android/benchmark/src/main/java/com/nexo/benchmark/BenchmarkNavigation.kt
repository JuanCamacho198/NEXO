package com.nexo.benchmark

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue

/** Application id under test (`:app` `applicationId`). */
internal const val TARGET_PACKAGE = "com.nexo"

/** Measured iterations per journey (macrobenchmark's documented minimum for a stable percentile set). */
internal const val ITERATIONS = 5

/** Fling/scroll repetitions inside one measured block. */
internal const val REPETITIONS = 3

/** Node-wait budget for a navigation lookup, in ms. */
internal const val NAV_TIMEOUT_MS = 10_000L

private const val POLL_MS = 200L
private const val GESTURE_MARGIN_DIVISOR = 5
private const val SCROLL_PERCENT = 0.5f

/**
 * Bottom-bar destinations by their shipped labels.
 *
 * The app exposes no `testTag`s, so the journeys match the localized label text.
 * Both shipped locales are listed: the CI emulator (S10) runs English by default,
 * but a Spanish-configured device must not silently fail to navigate.
 */
internal enum class BottomTab(
    val labels: List<String>,
) {
    HOME(listOf("Home", "Inicio")),
    LIBRARY(listOf("Library", "Estantería")),
    DISCOVER(listOf("Discover", "Descubrir")),
    ;

    /** English label — used in error messages only. */
    val display: String get() = labels.first()

    fun findOn(scope: MacrobenchmarkScope): UiObject2? = labels.firstNotNullOfOrNull { scope.device.findObject(By.text(it)) }
}

/**
 * Polls the given labels until one appears, or returns `null` at the timeout.
 *
 * Polling (instead of one blocking `Until.findObject` per label) keeps the wait
 * budget single-valued, so a non-English device does not pay the timeout twice.
 */
internal fun MacrobenchmarkScope.awaitAnyText(labels: List<String>): UiObject2? {
    val deadline = SystemClock.uptimeMillis() + NAV_TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
        labels.firstNotNullOfOrNull { device.findObject(By.text(it)) }?.let { return it }
        SystemClock.sleep(POLL_MS)
    }
    return null
}

/** Clicks a bottom-bar tab using its shipped-locale labels. */
internal fun MacrobenchmarkScope.navigateTo(tab: BottomTab) {
    val node =
        awaitAnyText(tab.labels)
            ?: error("Bottom-nav tab '${tab.display}' not found in either shipped locale. Is the session authenticated?")
    node.click()
    device.waitForIdle()
}

/**
 * True when the authenticated shell is on screen, i.e. when the bottom bar exists.
 *
 * `NexoNavHost` composes its `Scaffold.bottomBar` ONLY `if (isAuthenticated)`
 * (NexoNavHost.kt:270), so an unauthenticated process cold-starts into the Auth
 * graph with NO bottom-bar destinations to reach. That is a structural property of
 * the app, not a broken benchmark environment: a journey targeting Library/Discover
 * is genuinely INAPPLICABLE without a session, exactly like baseline-profile
 * collection on an API < 33 device.
 *
 * Probes the shipped bottom-bar labels against the same wait budget [navigateTo]
 * uses, so the two agree by construction — if this returns true, [navigateTo] for
 * any tab cannot then fail with the not-found error.
 */
internal fun MacrobenchmarkScope.hasAuthenticatedShell(): Boolean = BottomTab.entries.any { tab -> awaitAnyText(tab.labels) != null }

/**
 * Skips the calling journey when there is no authenticated session, instead of
 * failing inside [navigateTo].
 *
 * Seeding a session is explicitly out of scope (credentials or an auth bypass do
 * not belong in CI), and reducing the journey to the Auth surface would measure
 * something other than the journey's name — worse than an honest skip. `assumeTrue`
 * is the same mechanism `BaselineProfileGenerator.requireProfileCapableDevice`
 * already uses for an infeasible environment.
 */
internal fun MacrobenchmarkScope.assumeAuthenticatedSession() {
    assumeTrue(
        "Journey needs an authenticated session; the app cold-started into Auth and the bottom bar is not composed",
        hasAuthenticatedShell(),
    )
}

/** First scrollable container on the current screen. */
internal fun MacrobenchmarkScope.scrollable(): UiObject2 =
    device.wait(Until.findObject(By.scrollable(true)), NAV_TIMEOUT_MS)
        ?: error("No scrollable container found for '${device.currentPackageName}'")

/** Flings the first scrollable container down. */
internal fun MacrobenchmarkScope.flingContent(repetitions: Int = REPETITIONS) {
    val list = scrollable()
    // Keep gestures off the screen edges so the system gesture strips cannot
    // swallow a fling (a swallowed gesture would measure nothing).
    list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
    repeat(repetitions) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
}

/** Scrolls (no fling) the first scrollable container down. */
internal fun MacrobenchmarkScope.scrollContent(repetitions: Int = REPETITIONS) {
    val list = scrollable()
    list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
    repeat(repetitions) {
        list.scroll(Direction.DOWN, SCROLL_PERCENT)
        device.waitForIdle()
    }
}
