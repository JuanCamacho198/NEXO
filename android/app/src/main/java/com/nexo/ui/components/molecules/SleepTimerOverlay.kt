package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme

/**
 * Full-screen overlay shown when the sleep timer fires. Centers a
 * small "timer finished" card over a 60% scrim; any tap on the
 * screen dismisses it via [onDismiss].
 *
 * @param onDismiss Invoked when the user taps anywhere — the
 *   scrim, the card itself, or any empty area. There is no
 *   explicit "OK" button; tapping is the dismissal gesture.
 *
 * **Visual**: full-screen `Box` with `colorScheme.scrim` at 60%
 *   alpha as the background. Centered `Card` (24dp rounded, 8dp
 *   elevation, `surface` background) with 32dp padding. Inside: 48sp
 *   stopwatch emoji `⏰`, `headlineSmall` bold title
 *   (`R.string.reader_sleep_timer_finished_title`), and a
 *   `bodyMedium` subtitle
 *   (`R.string.reader_sleep_timer_tap_to_dismiss`) in
 *   `onSurfaceVariant`.
 * **Behavior**: any tap → [onDismiss]. No internal state, no
 *   auto-dismiss timer.
 * **Recomposition**: recomposes only when [onDismiss] changes.
 */
@Composable
fun SleepTimerOverlay(
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            shape = RoundedCornerShape(24.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Timer icon (emoji for simplicity, no extra dependency)
                Text(
                    text = "\u23F0",
                    fontSize = 48.sp,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.reader_sleep_timer_finished_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.reader_sleep_timer_tap_to_dismiss),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SleepTimerOverlayDarkPreview() {
    NexoTheme(darkTheme = true) {
        SleepTimerOverlay(onDismiss = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun SleepTimerOverlayLightPreview() {
    NexoTheme(darkTheme = false) {
        SleepTimerOverlay(onDismiss = {})
    }
}
