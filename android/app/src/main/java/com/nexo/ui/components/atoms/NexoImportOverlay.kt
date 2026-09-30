package com.nexo.ui.components.atoms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.library.BookImportState
import com.nexo.ui.icons.NexoIcons

/**
 * Animated full-screen import overlay that covers the content area
 * (below the bottom navigation bar) during book import.
 *
 * Displays a semi-transparent scrim, a centered card with a pulsing
 * upload icon, the current stage label, and a [LinearProgressIndicator].
 *
 * Entrance: scale-in (0.8f → 1.0f) + fade-in over 300ms.
 * Exit: scale-out + fade-out over 200ms.
 *
 * @param importState Current [BookImportState]; overlay is visible when not [BookImportState.Idle].
 * @param modifier   Modifier for the root Box.
 */
@Composable
fun NexoImportOverlay(
    importState: BookImportState,
    modifier: Modifier = Modifier,
) {
    val visible = importState !is BookImportState.Idle

    // ── Pulsing icon animation ──────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "importPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = androidx.compose.animation.core.EaseInOutCubic),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseScale",
    )

    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(animationSpec = tween(300)) +
                scaleIn(
                    initialScale = 0.8f,
                    animationSpec = tween(300, easing = EaseOutCubic),
                ),
        exit =
            fadeOut(animationSpec = tween(200)) +
                scaleOut(
                    targetScale = 0.8f,
                    animationSpec = tween(200, easing = EaseInCubic),
                ),
    ) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* consume touches — no dismiss */ },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            // ── Centered card ───────────────────────────────────────────
            Card(
                modifier =
                    Modifier
                        .widthIn(min = 200.dp, max = 300.dp),
                shape = RoundedCornerShape(16.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Pulsing upload icon
                    Icon(
                        imageVector = NexoIcons.Upload,
                        contentDescription = stringResource(R.string.import_overlay_icon_desc),
                        modifier =
                            Modifier
                                .size(48.dp)
                                .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale),
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stage label
                    val stageText =
                        when (importState) {
                            is BookImportState.Extracting -> stringResource(R.string.import_state_extracting)
                            is BookImportState.Analyzing -> stringResource(R.string.import_state_analyzing)
                            is BookImportState.Saving -> stringResource(R.string.import_state_saving)
                            else -> ""
                        }
                    Text(
                        text = stageText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress bar
                    val progress =
                        when (importState) {
                            is BookImportState.Extracting -> importState.progress
                            is BookImportState.Analyzing -> importState.progress
                            is BookImportState.Saving -> importState.progress
                            else -> 0f
                        }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoImportOverlayDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoImportOverlay(
            importState = BookImportState.Extracting(progress = 0.6f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoImportOverlayLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoImportOverlay(
            importState = BookImportState.Extracting(progress = 0.6f),
        )
    }
}
