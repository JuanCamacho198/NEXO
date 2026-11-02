package com.nexo.ui.components.atoms

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexo.presentation.theme.NexoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented rendering tests for [NexoSnackbar].
 *
 * These prove the behavioural contract the redesign had to preserve while
 * changing the surface: the message reaches the screen through the Material
 * `SnackbarHost`, and the host's own `SnackbarData` still drives it — the
 * composable reads its content rather than re-implementing dismissal.
 *
 * The animation itself is pinned by [NexoSnackbarMotionTest] on the JVM
 * (durations, curves, offsets, reduced-motion collapse); this test covers what
 * only a real composition can show.
 */
@RunWith(AndroidJUnit4::class)
class NexoSnackbarTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun showSnackbar(message: String) {
        composeRule.setContent {
            NexoTheme(darkTheme = true) {
                val hostState = remember { SnackbarHostState() }
                LaunchedEffect(hostState) {
                    hostState.showSnackbar(
                        message = message,
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite,
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    SnackbarHost(hostState = hostState) { data ->
                        NexoSnackbar(snackbarData = data)
                    }
                }
            }
        }
    }

    @Test
    fun snackbar_rendersTheHostMessage() {
        showSnackbar("Copied to clipboard")

        composeRule.onNodeWithText("Copied to clipboard").assertIsDisplayed()
    }

    @Test
    fun snackbar_rendersAMessageCarriedByTheHostData() {
        // Proves the surface really reads SnackbarData.visuals rather than
        // hardcoding anything: a different message must reach the screen.
        showSnackbar("Definition saved")

        composeRule.onNodeWithText("Definition saved").assertIsDisplayed()
    }
}
