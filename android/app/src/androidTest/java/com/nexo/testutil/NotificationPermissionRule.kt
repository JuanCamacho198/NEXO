package com.nexo.testutil

import androidx.test.platform.app.InstrumentationRegistry
import com.nexo.data.session.NotificationPermissionPrefs
import org.junit.rules.ExternalResource

/**
 * Marks `POST_NOTIFICATIONS` as already requested *before* the Activity under
 * test starts, so `MainActivity.onCreate`'s one-shot request is a no-op.
 *
 * Why a rule instead of `@Before`: `createAndroidComposeRule<MainActivity>()`
 * launches the Activity from inside its own `apply`d `Statement.evaluate()`,
 * which runs **before** JUnit's `@Before` methods (verified from the
 * `AndroidComposeTestRule$apply$1` / `ExternalResource$1` bytecode).
 * `MainActivity` went `RESUMED -> PAUSED` roughly 100 ms in because
 * `GrantPermissionsActivity` took focus, and every assertion after that threw
 * `IllegalStateException: No compose hierarchies found in the app` — the
 * hierarchy is only reachable while the Activity holds the focused window.
 *
 * Chain this rule *outside* the Compose rule so its `before()` runs first:
 *
 * ```
 * private val composeRule = createAndroidComposeRule<MainActivity>()
 *
 * @get:Rule
 * val rule = RuleChain.outerRule(NotificationPermissionRule()).around(composeRule)
 * ```
 *
 * `NotificationPermissionPrefs.markRequested` uses `apply()`, which commits to
 * the in-memory `SharedPreferences` immediately (the disk write is the async
 * part). `SharedPreferences` instances are per-process singletons, and this
 * runs in the same process as the Activity, so the later read sees the flag.
 */
class NotificationPermissionRule : ExternalResource() {
    override fun before() {
        NotificationPermissionPrefs.markRequested(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }
}
