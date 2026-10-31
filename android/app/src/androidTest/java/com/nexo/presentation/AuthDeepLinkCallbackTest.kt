package com.nexo.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexo.MainActivity
import com.nexo.testutil.NotificationPermissionRule
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthDeepLinkCallbackTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * [NotificationPermissionRule] is chained **outside** `composeRule` so it
     * runs before the Compose rule launches `MainActivity`.
     *
     * This ordering is load-bearing, not stylistic. `createAndroidComposeRule`
     * starts the Activity from inside its own `apply`d `Statement.evaluate()`,
     * which runs BEFORE JUnit's `@Before` methods (verified from the
     * `AndroidComposeTestRule$apply$1` / `ExternalResource$1` bytecode:
     * `ExternalResource` calls `before()` at offset 4 and `base.evaluate()` at
     * offset 19). A `@Before` therefore wrote the one-shot flag one Activity
     * launch too late, `MainActivity.onCreate` still requested
     * `POST_NOTIFICATIONS`, `GrantPermissionsActivity` took focus, and
     * `MainActivity` went `RESUMED -> PAUSED` about 100 ms in (CI logcat:
     * `01:30:13.644 RESUMED` then `01:30:13.744 PAUSED`).
     *
     * Once paused, `onAllNodesWithText(...).fetchSemanticsNodes()` throws
     * `IllegalStateException: No compose hierarchies found in the app`, and
     * `waitUntil` does NOT swallow predicate exceptions — it retries only when
     * the predicate returns `false`. So the `waitUntil` that was meant to add
     * patience the previous commit introduced failed immediately at its own
     * first line, which is the stack trace CI recorded.
     *
     * Marking the flag in a rule that runs before the launch removes the prompt
     * entirely, keeping the Activity resumed and its hierarchy focused for the
     * whole test. See [NotificationPermissionRule] for the `apply()`-is-in-memory
     * reasoning.
     */
    @get:Rule
    val rule: RuleChain =
        RuleChain
            .outerRule(NotificationPermissionRule())
            .around(composeRule)

    @Ignore(
        "Asserts behaviour the app does not have. AuthViewModel reads the session once, in " +
            "init; MainActivity.onNewIntent imports it so the NEXT launch restores it, which is " +
            "the documented cold-start flow. A warm-start callback cannot produce authenticated " +
            "state in this Activity instance. The final assertion also looks for 'Import EPUB', " +
            "which nothing renders - the library shows 'Import book' (library_import_book), and " +
            "the three 'Import EPUB' strings are baselined as unused. See the ignored-test issue.",
    )
    @Test
    fun callbackIntent_onNewIntent_propagatesToAuthenticatedLibraryState() {
        waitForText("Continue with Google", SIGN_IN_BUTTON_TIMEOUT_MS)
        composeRule.onNodeWithText("Continue with Google").assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            val callbackIntent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("nextpage://auth/callback?access_token=test-token&user_id=deeplink-user"),
                ).apply {
                    setClass(activity, MainActivity::class.java)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            activity.startActivity(callbackIntent)
        }

        waitForText("Import EPUB", DEEP_LINK_TIMEOUT_MS)
        composeRule.onNodeWithText("Import EPUB").assertIsDisplayed()
    }

    /**
     * Waits for a node with [text] to enter the semantics tree, then returns.
     *
     * The predicate wraps the node lookup so that a transiently missing
     * hierarchy — the exact condition that produced "No compose hierarchies
     * found in the app" — is treated as "not ready yet" rather than as a test
     * failure. `waitUntil` retries only on `false`; an exception thrown inside
     * the lambda propagates straight out, which is what defeated the bare
     * lookup the previous commit had here.
     *
     * The timeout is deliberate: [DEEP_LINK_TIMEOUT_MS] must exceed the
     * emulator's worst observed frame stall (CI logcat recorded
     * `Skipped 138 frames!`), and the assertion after it stays strict — a node
     * that never appears still fails the test, just after a bounded wait.
     */
    private fun waitForText(
        text: String,
        timeoutMillis: Long,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private companion object {
        /** Startup on the CI emulator is slow; the splash holds until the session restore lands. */
        const val SIGN_IN_BUTTON_TIMEOUT_MS = 30_000L

        /**
         * Generous headroom over the 10 s the previous commit used. The deep-link
         * path crosses `onNewIntent` -> supabase-kt `handleDeeplinks` and a
         * navigation settle, on an emulator that skips >100 frames per start.
         */
        const val DEEP_LINK_TIMEOUT_MS = 20_000L
    }
}
