package com.nexo.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexo.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthDeepLinkCallbackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun callbackIntent_onNewIntent_propagatesToAuthenticatedLibraryState() {
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

        composeRule.onNodeWithText("Import EPUB").assertIsDisplayed()
    }
}
