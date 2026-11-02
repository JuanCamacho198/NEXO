package com.nexo.ui.components.atoms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons
import kotlinx.coroutines.currentCoroutineContext

/** Corner radius for the snackbar surface — the brand card radius. */
private val SnackbarCornerRadius = RoundedCornerShape(NexoDimens.cardCornerRadius)

/** Hairline brand border, matching the outline used on cards and panels. */
private val SnackbarBorder = BorderStroke(1.dp, NexoColors.borderSubtle)

/** Caps the surface width on tablets so a message keeps a readable line length. */
private val SnackbarMaxWidth = 560.dp

/**
 * Identity-styled snackbar for NEXO.
 *
 * Replaces Material 3's inverse-surface defaults with the brand tokens:
 * `surface` background, `borderSubtle` hairline, `textPrimary` message and
 * `primary` for the optional action. The result reads as a NEXO panel that
 * happens to float, rather than a system toast.
 *
 * @param snackbarData The current message and optional action, supplied by the
 *   active [SnackbarHostState]. Read on every recomposition.
 * @param modifier Modifier applied to the snackbar container.
 *
 * **Visual**: brand tokens (above), 12dp radius, 16dp horizontal padding.
 * **Behavior**: dismissal and action handling stay entirely with Material 3 —
 * [snackbarData] keeps its own duration, and its dismiss/action callbacks are
 * forwarded untouched, so `showSnackbar` suspends and resolves exactly as it
 * did before. A queued message replaces the current one in place.
 * **Motion**: slides up from below with a fade and a 0.96 -> 1.0 scale over
 * 200ms; leaves with a fade and a short downward slide over 180ms. See
 * [NexoSnackbarMotion]. Both collapse when the platform reports reduced motion.
 * **Recomposition**: recomposes when the host emits a new [snackbarData].
 */
@Composable
fun NexoSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    SnackbarSurface(
        message = snackbarData.visuals.message,
        actionLabel = snackbarData.visuals.actionLabel,
        withDismissAction = snackbarData.visuals.withDismissAction,
        onAction = snackbarData::performAction,
        onDismiss = snackbarData::dismiss,
        modifier = modifier,
    )
}

/**
 * Stateless body of [NexoSnackbar].
 *
 * Split from the Material [SnackbarData] contract so the surface can be
 * previewed and driven directly. [NexoSnackbar] is the production entry point
 * and forwards its data here unchanged.
 */
@Composable
internal fun SnackbarSurface(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    onAction: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    // Scale-only motion probe: `MotionDurationScale` lives in the Recomposer's
    // coroutine context rather than in a CompositionLocal, so it is read once
    // here. Compose already applies this scale to the animation *durations*;
    // reading it here additionally collapses the travel distance and the start
    // scale, so "remove animations" really does mean no motion.
    var motionScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(Unit) {
        motionScale =
            currentCoroutineContext()[MotionDurationScale.Key]?.scaleFactor ?: 1f
    }
    val startScale = NexoSnackbarMotion.enterStartScale(motionScale)

    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = true,
            enter =
                slideInVertically(
                    animationSpec = NexoSnackbarMotion.enterSpec(),
                    // Overshoot relative to the surface's own height: the exact
                    // height is not known before layout, and any excess simply
                    // sits further off-screen, so no feedback loop is needed.
                    initialOffsetY = { NexoSnackbarMotion.enterOffset(it, motionScale).y },
                ) +
                    fadeIn(animationSpec = NexoSnackbarMotion.enterSpec()) +
                    scaleIn(
                        initialScale = startScale,
                        animationSpec = NexoSnackbarMotion.enterSpec(),
                    ),
            exit =
                slideOutVertically(
                    animationSpec = NexoSnackbarMotion.exitSpec(),
                    targetOffsetY = { NexoSnackbarMotion.exitOffset(it, motionScale).y },
                ) +
                    fadeOut(animationSpec = NexoSnackbarMotion.exitSpec()),
        ) {
            Row(
                modifier =
                    Modifier
                        .widthIn(max = SnackbarMaxWidth)
                        .clip(SnackbarCornerRadius)
                        .background(NexoColors.surface)
                        .border(SnackbarBorder, SnackbarCornerRadius)
                        .padding(
                            horizontal = NexoDimens.spacingMd,
                            vertical = NexoDimens.spacingSm + 2.dp,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexoColors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (actionLabel != null) {
                    TextButton(onClick = onAction) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = NexoColors.primary,
                        )
                    }
                }
                if (withDismissAction) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = NexoIcons.Close,
                            contentDescription = null,
                            tint = NexoColors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SnackbarDarkPreview() {
    NexoTheme(darkTheme = true) {
        SnackbarPreviewScaffold()
    }
}

@Preview(showBackground = true)
@Composable
private fun SnackbarLightPreview() {
    NexoTheme(darkTheme = false) {
        SnackbarPreviewScaffold()
    }
}

@Composable
private fun SnackbarPreviewScaffold() {
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
