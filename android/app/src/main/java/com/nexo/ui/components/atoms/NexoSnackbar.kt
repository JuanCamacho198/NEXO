package com.nexo.ui.components.atoms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme

/**
 * Material 3 `Snackbar` pre-colored with the NEXO inverse theme
 * tokens. Use as the `snackbar` slot of `Scaffold` driven by a
 * `SnackbarHostState`.
 *
 * @param snackbarData The current message and optional action, provided
 *   by the `SnackbarHostState.showSnackbar` result. Re-read on every
 *   recomposition from the `SnackbarHostState`.
 * @param modifier Modifier applied to the snackbar container.
 *
 * **Visual**: inverse-themed — `colorScheme.inverseSurface` background,
 * `colorScheme.inverseOnSurface` text, `colorScheme.inversePrimary`
 * action label.
 * **Behavior**: delegates dismissal and action handling to Material 3.
 * The snackbar auto-dismisses according to `snackbarData.duration` and
 * forwards the action callback from `snackbarData`.
 * **Recomposition**: recomposes whenever the host state emits a new
 * `SnackbarData` (e.g. after `showSnackbar` is awaited and a new
 * message is queued).
 */
@Composable
fun NexoSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    Snackbar(
        modifier = modifier,
        snackbarData = snackbarData,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        actionColor = MaterialTheme.colorScheme.inversePrimary,
    )
}

@Preview(showBackground = true)
@Composable
private fun SnackbarDarkPreview() {
    NexoTheme(darkTheme = true) {
        val hostState = remember { SnackbarHostState() }
        LaunchedEffect(hostState) {
            hostState.showSnackbar(
                message = "Sample message",
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

@Preview(showBackground = true)
@Composable
private fun SnackbarLightPreview() {
    NexoTheme(darkTheme = false) {
        val hostState = remember { SnackbarHostState() }
        LaunchedEffect(hostState) {
            hostState.showSnackbar(
                message = "Sample message",
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
