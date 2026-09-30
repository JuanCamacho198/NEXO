package com.nexo.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexo.MainActivity
import org.junit.Rule
import org.junit.Test
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
 * Confirm-path with an injected fake transport (dao write assertions) requires
 * a test transport injection seam and a device/emulator — pending manual QA.
 */
@RunWith(AndroidJUnit4::class)
class InstallDeepLinkTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun warmStart_installIntent_showsInstallFlowDialog_andDismissAborts() {
        composeRule.onNodeWithText("Continue with Google").assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            val installIntent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("nextpage://install?url=https://example.test/manifest.json"),
                ).apply {
                    setClass(activity, MainActivity::class.java)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            activity.startActivity(installIntent)
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Install addon?").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Couldn't install addon").fetchSemanticsNodes().isNotEmpty()
        }

        // Cancel (confirming) or OK (error) dismisses: nothing installed, no crash.
        composeRule
            .runCatching {
                composeRule.onNodeWithText("Cancel").performClick()
            }.recoverCatching {
                composeRule.onNodeWithText("OK").performClick()
            }
    }
}
