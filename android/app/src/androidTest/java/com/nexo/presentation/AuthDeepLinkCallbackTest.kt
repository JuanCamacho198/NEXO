package com.nexo.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexo.MainActivity
import com.nexo.data.session.NotificationPermissionPrefs
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthDeepLinkCallbackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * `MainActivity.onCreate` requests `POST_NOTIFICATIONS` once per install
     * (API 33+) and records that in [NotificationPermissionPrefs]. On the CI
     * emulator (API 34) that dialog is a separate
     * `GrantPermissionsActivity`, so MainActivity goes RESUMED -> PAUSED and
     * its Compose hierarchy is no longer the focused window — every
     * `onNodeWithText` then fails with "No compose hierarchies found"
     * (verified in CI logcat: `Displayed com.google.android
     * .permissioncontroller/...GrantPermissionsActivity`).
     *
     * Marking the one-shot flag first makes the request a no-op, so the
     * Activity stays resumed and the assertion runs against a live hierarchy.
     * This exercises the app's own "already asked" path — production
     * behaviour is unchanged, and no permission rule/dependency is added.
     */
    @Before
    fun suppressNotificationPermissionPrompt() {
        NotificationPermissionPrefs.markRequested(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
    }

    @Test
    fun callbackIntent_onNewIntent_propagatesToAuthenticatedLibraryState() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Continue with Google").fetchSemanticsNodes().isNotEmpty()
        }
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

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Import EPUB").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Import EPUB").assertIsDisplayed()
    }
}
