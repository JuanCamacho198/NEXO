package com.nexo.ui.components.atoms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Animated full-screen overlay shown while a cross-device book download is in
 * progress.
 *
 * Displays a semi-transparent scrim, a centered card with the book cover (or a
 * pulsing download icon when no cover is available), the book title, a stage
 * label, a progress bar, and a percent readout.
 *
 * The progress bar is fake-but-plausible — it is NOT driven by real bytes. It
 * eases to 0.4, then crawls asymptotically toward 0.88 (EaseOutCubic, slowing
 * down and never claiming completion) until the download actually finishes,
 * at which point [isCompleted] snaps the bar to 100%.
 *
 * Entrance: scale-in (0.8f → 1.0f) + fade-in over 300ms.
 * Exit: scale-out + fade-out over 200ms.
 *
 * @param bookTitle   Title of the book being downloaded.
 * @param coverUrl    Remote cover URL; when blank a pulsing icon is shown.
 * @param isCompleted `true` when the download finished (bar snaps to 100%).
 * @param visible     Whether the overlay is shown.
 * @param modifier    Modifier for the root Box.
 */
@Composable
fun NexoDownloadOverlay(
    bookTitle: String,
    coverUrl: String?,
    isCompleted: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }

    // Fake-progress animation: ease to 0.4, then asymptotically toward 0.88.
    // If the download completes early, the key change cancels this effect and
    // the completion branch snaps the bar to 100%.
    LaunchedEffect(visible, isCompleted) {
        when {
            !visible -> Unit
            isCompleted -> progress.animateTo(1f, tween(300, easing = EaseOutCubic))
            else -> {
                progress.animateTo(0.4f, tween(500, easing = FastOutSlowInEasing))
                progress.animateTo(0.88f, tween(6000, easing = EaseOutCubic))
            }
        }
    }

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
                    // Book cover, or pulsing icon when no cover is available.
                    if (!coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(width = 64.dp, height = 96.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        PulsingDownloadIcon()
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = bookTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stage label (only meaningful while downloading).
                    val stageText =
                        if (isCompleted) {
                            stringResource(R.string.download_overlay_done)
                        } else {
                            when {
                                progress.value < 0.6f ->
                                    stringResource(R.string.download_overlay_stage_downloading)
                                progress.value < 0.85f ->
                                    stringResource(R.string.download_overlay_stage_processing)
                                else ->
                                    stringResource(R.string.download_overlay_stage_saving)
                            }
                        }
                    Text(
                        text = stageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { progress.value },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Percent readout makes the fake progress feel real.
                    Text(
                        text =
                            stringResource(
                                R.string.download_overlay_percent,
                                (progress.value * 100).toInt(),
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Pulsing cloud-download icon shown while a book without a cover URL downloads.
 */
@Composable
private fun PulsingDownloadIcon() {
    val infiniteTransition = rememberInfiniteTransition(label = "downloadPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = EaseInOutCubic),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseScale",
    )

    Icon(
        imageVector = NexoIcons.CloudDownload,
        contentDescription = null,
        modifier =
            Modifier
                .size(48.dp)
                .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale),
        tint = MaterialTheme.colorScheme.primary,
    )
}

@Preview(showBackground = true)
@Composable
private fun NexoDownloadOverlayDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoDownloadOverlay(
            bookTitle = "Sample book",
            coverUrl = null,
            isCompleted = false,
            visible = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoDownloadOverlayLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoDownloadOverlay(
            bookTitle = "Sample book",
            coverUrl = null,
            isCompleted = false,
            visible = true,
        )
    }
}
