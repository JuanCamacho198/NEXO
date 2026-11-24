package com.nexo.ui.components.atoms

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.viewmodel.library.BookImportState
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

/**
 * Full-screen import overlay: a scrim that blocks touches and a card with an
 * orange progress ring around a floating book, the current stage, and three
 * stage dots (extract, analyse, save).
 *
 * Keep this composed while idle and let [importState] drive it: the overlay
 * animates in when an import starts and out when it returns to
 * [BookImportState.Idle]. Composing it only while busy (as the host used to)
 * meant it was already visible on its first frame, so neither the entrance nor
 * the exit ever played. The scrim only fades; the card alone springs in, so the
 * backdrop no longer shrinks with it.
 *
 * @param importState Current [BookImportState]; visible while not [BookImportState.Idle].
 * @param modifier Modifier for the root Box.
 */
@Composable
fun NexoImportOverlay(
    importState: BookImportState,
    modifier: Modifier = Modifier,
) {
    val active = importState !is BookImportState.Idle
    // The last busy stage, so the card fades out still showing it instead of
    // flashing an empty label on the way out.
    var shown by remember { mutableStateOf(importState) }
    if (active) shown = importState

    AnimatedVisibility(
        visible = active,
        enter = fadeIn(tween(ScrimFadeInMs)),
        exit = fadeOut(tween(ScrimFadeOutMs)),
    ) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = ScrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* consume touches — no dismiss */ },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            ImportCard(
                state = shown,
                modifier =
                    Modifier.animateEnterExit(
                        enter =
                            scaleIn(initialScale = 0.85f, animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) +
                                fadeIn(tween(ScrimFadeInMs)),
                        exit = scaleOut(targetScale = 0.94f, animationSpec = tween(ScrimFadeOutMs)) + fadeOut(tween(ScrimFadeOutMs)),
                    ),
            )
        }
    }
}

@Composable
private fun ImportCard(
    state: BookImportState,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val shape = RoundedCornerShape(28.dp)
    val stage = state.stageIndex()
    val progress by animateFloatAsState(
        targetValue = state.progressValue(),
        animationSpec = tween(ProgressMs, easing = FastOutSlowInEasing),
        label = "importProgress",
    )
    Column(
        modifier =
            modifier
                .width(CardWidth)
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.borderSubtle, shape)
                .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProgressRing(progress = progress)
        Spacer(modifier = Modifier.height(20.dp))
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                (fadeIn(tween(LabelMs)) + slideInVertically { it / 2 }) togetherWith
                    (fadeOut(tween(LabelMs / 2)) + slideOutVertically { -it / 2 })
            },
            label = "importStage",
        ) { index ->
            Text(
                text =
                    when (index) {
                        0 -> stringResource(R.string.import_state_extracting)
                        1 -> stringResource(R.string.import_state_analyzing)
                        else -> stringResource(R.string.import_state_saving)
                    },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        StageDots(current = stage)
    }
}

/**
 * Ring that fills to [progress] around a book that gently bobs, so the card
 * still reads as alive while one stage takes a while.
 */
@Composable
private fun ProgressRing(progress: Float) {
    val colors = nexoColors
    val bob by rememberInfiniteTransition(label = "importBook").animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BookBobMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "importBookBob",
    )
    Box(modifier = Modifier.size(RingSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = RingStroke.toPx()
            val inset = stroke / 2
            val arcSize =
                androidx.compose.ui.geometry
                    .Size(size.width - stroke, size.height - stroke)
            val topLeft =
                androidx.compose.ui.geometry
                    .Offset(inset, inset)
            drawArc(
                // A tint of the secondary text: surfaceVariant vanished on the dark card.
                color = colors.textSecondary.copy(alpha = 0.18f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = colors.accentOrange,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .graphicsLayer { translationY = bob * BookBobDistance.toPx() }
                    .clip(CircleShape)
                    .background(colors.accentOrange.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NexoIcons.BookOpen,
                contentDescription = stringResource(R.string.import_overlay_icon_desc),
                tint = colors.accentOrange,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** Three dots for the three stages: done ones filled, the current one stretched into a pill. */
@Composable
private fun StageDots(current: Int) {
    val colors = nexoColors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(STAGE_COUNT) { index ->
            val width by animateDpAsState(
                targetValue = if (index == current) 22.dp else 8.dp,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                label = "stageDotWidth",
            )
            val color by animateColorAsState(
                targetValue = if (index <= current) colors.accentOrange else colors.surfaceVariant,
                animationSpec = tween(LabelMs),
                label = "stageDotColor",
            )
            Box(
                modifier =
                    Modifier
                        .height(8.dp)
                        .width(width)
                        .clip(CircleShape)
                        .background(color),
            )
        }
    }
}

private fun BookImportState.stageIndex(): Int =
    when (this) {
        is BookImportState.Extracting -> 0
        is BookImportState.Analyzing -> 1
        is BookImportState.Saving -> 2
        BookImportState.Idle -> 0
    }

private fun BookImportState.progressValue(): Float =
    when (this) {
        is BookImportState.Extracting -> progress
        is BookImportState.Analyzing -> progress
        is BookImportState.Saving -> progress
        BookImportState.Idle -> 0f
    }

private const val STAGE_COUNT = 3
private const val ScrimAlpha = 0.5f
private const val ScrimFadeInMs = 220
private const val ScrimFadeOutMs = 260
private const val ProgressMs = 600
private const val LabelMs = 240
private const val BookBobMs = 1400
private val BookBobDistance = 3.dp
private val RingSize = 112.dp
private val RingStroke = 6.dp
private val CardWidth = 272.dp

@Preview(showBackground = true)
@Composable
private fun NexoImportOverlayDarkPreview() {
    NexoTheme(darkTheme = true) {
        ImportCard(state = BookImportState.Analyzing(progress = 0.6f))
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoImportOverlayLightPreview() {
    NexoTheme(darkTheme = false) {
        ImportCard(state = BookImportState.Extracting(progress = 0.3f))
    }
}
