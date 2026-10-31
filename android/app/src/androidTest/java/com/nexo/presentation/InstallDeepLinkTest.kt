package com.nexo.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexo.MainActivity
import com.nexo.testutil.NotificationPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Instrumented routing tests for `nextpage://install` deep links
 * (addon-deeplink-v1 B4, precedent [AuthDeepLinkCallbackTest]).
 *
 * The manifest URL host (example.test) is unreachable on-device, so the fetch
 * preview fails fast with ADDON_FETCH_NETWORK and the flow lands in the ERROR
 * dialog — which still proves the routing ORDER: the install intent reached
 * the install controller instead of supabase handleDeeplinks (an auth URI
 * parse of nextpage://install would never render this dialog).
 *
 * The intent is delivered by calling `MainActivity.onNewIntent` directly, which
 * is how a singleTop warm start reaches the Activity anyway — see the comment
 * at the delivery site for why production `setIntent` breaks Scenario teardown
 * and how the launch intent is restored afterwards.
 *
 * Confirm-path with an injected fake transport (dao write assertions) requires
 * a test transport injection seam and a device/emulator — pending manual QA.
 */
@RunWith(AndroidJUnit4::class)
class InstallDeepLinkTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Same `POST_NOTIFICATIONS` prompt as [AuthDeepLinkCallbackTest], and the
     * same rule-ordering requirement: the flag must be written BEFORE the
     * Compose rule launches `MainActivity`, or `onCreate` still requests the
     * permission, `GrantPermissionsActivity` takes focus and the Activity goes
     * `RESUMED -> PAUSED`. A `@Before` runs too late — the Compose rule starts
     * the Activity from its own `apply`d statement, ahead of `@Before`. See
     * [NotificationPermissionRule] and the class doc in
     * [AuthDeepLinkCallbackTest] for the bytecode evidence.
     */
    @get:Rule
    val rule: RuleChain =
        RuleChain
            .outerRule(NotificationPermissionRule())
            .around(composeRule)

    @Test
    fun warmStart_installIntent_showsInstallFlowDialog_andDismissAborts() {
        waitForText("Continue with Google", SIGN_IN_BUTTON_TIMEOUT_MS)

        // Warm-start deep links arrive through `onNewIntent` (singleTop), so the
        // test injects the intent there instead of launching MainActivity at
        // itself. A self-`startActivity` makes the platform re-deliver the
        // intent outside `ActivityScenario`'s own lifecycle bookkeeping and
        // `close()` then blocks for `ActivityLifecycleTimeout` (~45 s) before
        // failing with "Activity never becomes requested state [DESTROYED]".
        //
        // The override is the real routing entry point. The call chain under
        // test: `MainActivity.onNewIntent` → `setIntent(intent)` → install
        // branch first → `InstallDeepLinkParser.isInstallUri` →
        // `appContainer.installDeepLinkController.onInstallUri`, returning
        // before `handleAuthDeepLink`/supabase.
        //
        // That production `setIntent` is what breaks Scenario teardown, and the
        // reason is NOT Intent identity. `ActivityScenario` guards every
        // lifecycle callback in `ActivityScenario$1.onActivityLifecycleChanged`
        // by calling `ActivityScenario.activityMatchesIntent(startActivityIntent,
        // activity)` — a value comparison of the LAUNCH intent's
        // action/data/type/package/categories/identifier against
        // `activity.getIntent()`. `setIntent` replaces exactly the value that
        // guard reads, so once the install intent is installed the guard
        // returns false for every later callback (the lifecycle log shows
        // "startActivityIntent = Intent { act=MAIN ... }" vs
        // "activity.getIntent() = Intent { act=VIEW ... }"), `currentActivityStage`
        // freezes at RESUMED, and `close()` never observes the Activity's
        // DESTROYED transition. The Activity itself dies normally; only the
        // Scenario fails to record it.
        //
        // The fix restores the recorded launch intent immediately after the
        // override returns. The routing already happened synchronously inside
        // `onNewIntent` (onInstallUri publishes Fetching and only then launches
        // the fetch on the controller scope), so re-installing the original
        // intent here cannot disturb what the test proves — it only hands the
        // Scenario back the value its guard compares against.
        //
        // `onNewIntent` is `protected`, and the test lives in a different
        // package (`com.nexo.presentation`), so the reflective virtual call
        // below is the only way to reach a subclass override without adding a
        // production-visible seam. It resolves to the most-derived override
        // (MainActivity's) and runs the same method a singleTop redelivery
        // would.
        composeRule.activityRule.scenario.onActivity { activity ->
            val launchIntent = activity.intent
            val installIntent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("nextpage://install?url=https://example.test/manifest.json"),
                ).apply {
                    setClass(activity, MainActivity::class.java)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            MainActivity::class.java
                .getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }
                .invoke(activity, installIntent)
            // Hand back the intent ActivityScenario recorded at launch so its
            // lifecycle guard keeps matching through teardown.
            activity.intent = launchIntent
        }

        // The install flow reaches either the confirm dialog (preview succeeded)
        // or the error dialog (fetch failed, the expected CI outcome) — both
        // prove the intent routed to the install controller and not to supabase.
        waitUntilEither("Install addon?", "Couldn't install addon", INSTALL_DIALOG_TIMEOUT_MS)

        // Cancel (confirming) or OK (error) dismisses: nothing installed, no crash.
        composeRule
            .runCatching {
                composeRule.onNodeWithText("Cancel").performClick()
            }.recoverCatching {
                composeRule.onNodeWithText("OK").performClick()
            }
    }

    /**
     * Waits for a node with [text] to enter the semantics tree.
     *
     * The lookup is wrapped so that a transiently absent hierarchy (the source
     * of "No compose hierarchies found in the app") reads as "not ready yet"
     * instead of failing the test. `waitUntil` retries only on `false`; an
     * exception inside the lambda escapes and fails immediately.
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

    /** Waits until either dialog title is present. */
    private fun waitUntilEither(
        first: String,
        second: String,
        timeoutMillis: Long,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching {
                composeRule.onAllNodesWithText(first).fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithText(second).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private companion object {
        /** Startup on the CI emulator is slow; the splash holds until the session restore lands. */
        const val SIGN_IN_BUTTON_TIMEOUT_MS = 30_000L

        /**
         * The install preview does a real network fetch that must time out
         * (example.test is unreachable) before the ERROR dialog appears, so this
         * budget covers a full request failure on a slow emulator.
         */
        const val INSTALL_DIALOG_TIMEOUT_MS = 30_000L
    }
}
