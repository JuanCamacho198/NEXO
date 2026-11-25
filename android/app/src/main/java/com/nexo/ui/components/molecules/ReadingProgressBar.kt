package com.nexo.ui.components.molecules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.AccentOrange
import com.nexo.ui.theme.NexoTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The reader's footer: where you are in the book, and a bar to move through it.
 *
 * - The bar fills in the orange reading accent and marks each chapter's start
 *   with a notch ([chapterStarts]), so the book's structure shows at a glance.
 * - Dragging (or tapping) previews instead of jumping: the handle grows and a
 *   bubble above it names the chapter and percentage under the finger. The
 *   jump happens once, on release ([onScrubCommit]) — moving on every pointer
 *   event re-laid out the book dozens of times per drag.
 * - After a jump, "Back to where you were" stays offered for a few seconds
 *   ([canGoBack]), so an accidental scrub costs one tap.
 * - Below, the chapter title and the pages left in it sit on separate lines, so
 *   a long title ellipsizes on its own line and never eats the page count (they
 *   used to share one string, which cut the count off on long titles).
 *
 * The palette stays the reader chrome's fixed dark one (the bar sits on the
 * reader's dark footer in every reading theme); only the accent is the brand
 * orange.
 *
 * @param chapterTitle Cleaned title of the current chapter; empty when unknown.
 * @param chapterPagesLeft Pages after this one in the chapter, or null while unknown.
 * @param fallbackLabel Shown when there is no chapter title (PDF page "3 / 10").
 * @param chapterStarts Each chapter's start as a fraction of the book, `null` where unknown.
 * @param chapterTitles Titles aligned with [chapterStarts], named in the scrub bubble.
 */
@Composable
fun ReadingProgressBar(
    progressPercent: Float,
    chapterTitle: String,
    chapterPagesLeft: Int?,
    fallbackLabel: String,
    modifier: Modifier = Modifier,
    chapterStarts: List<Float?> = emptyList(),
    chapterTitles: List<String> = emptyList(),
    canGoBack: Boolean = false,
    onScrubCommit: ((Float) -> Unit)? = null,
    onGoBack: () -> Unit = {},
    onGoBackExpired: () -> Unit = {},
    onRotateScreen: (() -> Unit)? = null,
) {
    val progressFraction = progressPercent.coerceIn(0f, 100f) / 100f
    val animatedProgress by animateFloatAsState(targetValue = progressFraction, label = "progressFill")

    var trackWidth by remember { mutableIntStateOf(0) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shownFraction = dragFraction ?: animatedProgress
    val dragging = dragFraction != null
    val density = LocalDensity.current

    LaunchedEffect(canGoBack) {
        if (canGoBack) {
            delay(GO_BACK_OFFER_MS)
            onGoBackExpired()
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 16.dp),
    ) {
        // ── "Back to where you were" after a jump ──────────────────
        AnimatedVisibility(
            visible = canGoBack && !dragging,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Row(
                modifier =
                    Modifier
                        .padding(bottom = 12.dp)
                        .clip(CircleShape)
                        .background(AccentOrange.copy(alpha = 0.16f))
                        .border(1.dp, AccentOrange.copy(alpha = 0.5f), CircleShape)
                        .clickable(onClick = onGoBack)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(imageVector = NexoIcons.History, contentDescription = null, tint = AccentOrange, modifier = Modifier.size(16.dp))
                Text(
                    text = stringResource(R.string.reader_scrub_back),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
            }
        }

        // ── Track ──────────────────────────────────────────────────
        val trackHeight by animateDpAsState(if (dragging) 8.dp else 5.dp, label = "trackHeight")
        val thumbSize by animateDpAsState(
            targetValue = if (dragging) 24.dp else 16.dp,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
            label = "thumbSize",
        )
        val notchColor = FooterBackground
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(TOUCH_HEIGHT)
                    .onSizeChanged { trackWidth = it.width }
                    .semantics {
                        progressBarRangeInfo = ProgressBarRangeInfo(progressFraction, 0f..1f)
                        if (onScrubCommit != null) {
                            setProgress { target ->
                                onScrubCommit(target.coerceIn(0f, 1f) * 100f)
                                true
                            }
                        }
                    }.then(
                        if (onScrubCommit == null) {
                            Modifier
                        } else {
                            Modifier
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        if (trackWidth > 0) onScrubCommit((offset.x / trackWidth).coerceIn(0f, 1f) * 100f)
                                    }
                                }.pointerInput(Unit) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { offset ->
                                            if (trackWidth > 0) dragFraction = (offset.x / trackWidth).coerceIn(0f, 1f)
                                        },
                                        onHorizontalDrag = { change, _ ->
                                            change.consume()
                                            if (trackWidth > 0) dragFraction = (change.position.x / trackWidth).coerceIn(0f, 1f)
                                        },
                                        onDragEnd = {
                                            dragFraction?.let { onScrubCommit(it * 100f) }
                                            dragFraction = null
                                        },
                                        onDragCancel = { dragFraction = null },
                                    )
                                }
                        },
                    ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight)) {
                val radius = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(color = TrackColor, cornerRadius = radius)
                drawRoundRect(color = AccentOrange, size = Size(size.width * shownFraction, size.height), cornerRadius = radius)
                // Chapter notches: a thin cut in the bar at each chapter start.
                val starts = chapterStarts.filterNotNull()
                if (starts.size in 2..MAX_NOTCHES) {
                    val notchWidth = 2.dp.toPx()
                    starts.forEach { start ->
                        if (start > 0.005f && start < 0.995f) {
                            val x = size.width * start
                            drawRect(color = notchColor, topLeft = Offset(x - notchWidth / 2, 0f), size = Size(notchWidth, size.height))
                        }
                    }
                }
            }
            // Handle: white with an orange ring, so it reads as a grip, not as the fill's end.
            Box(
                modifier =
                    Modifier
                        .offset {
                            val x = (trackWidth * shownFraction) - thumbSize.toPx() / 2
                            IntOffset(x.roundToInt(), 0)
                        }.size(thumbSize)
                        .shadow(6.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, AccentOrange, CircleShape),
            )
            // Scrub preview bubble above the finger.
            val previewFraction = dragFraction
            if (previewFraction != null && trackWidth > 0) {
                val chapterIndex = previewChapterIndex(chapterStarts, previewFraction)
                val previewTitle = chapterIndex?.let { chapterTitles.getOrNull(it) }?.takeIf { it.isNotBlank() }
                var bubbleWidth by remember { mutableIntStateOf(0) }
                Column(
                    modifier =
                        Modifier
                            .offset {
                                val center = trackWidth * previewFraction
                                val x = (center - bubbleWidth / 2f).coerceIn(0f, (trackWidth - bubbleWidth).toFloat().coerceAtLeast(0f))
                                IntOffset(x.roundToInt(), with(density) { -BUBBLE_LIFT.roundToPx() })
                            }.onSizeChanged { bubbleWidth = it.width }
                            .widthIn(max = 240.dp)
                            .shadow(10.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(BubbleBackground)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (previewTitle != null) {
                        Text(
                            text = previewTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "${(previewFraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ── Where you are: chapter, pages left, percentage ─────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapterTitle.ifBlank { fallbackLabel.ifEmpty { stringResource(R.string.reader_progress_empty) } },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chapterPagesLeft != null && chapterTitle.isNotBlank()) {
                    Text(
                        text =
                            if (chapterPagesLeft == 0) {
                                stringResource(R.string.reader_chapter_last_page)
                            } else {
                                pluralStringResource(R.plurals.reader_chapter_pages_left, chapterPagesLeft, chapterPagesLeft)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = "${(shownFraction * 100).roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = AccentOrange,
                modifier = Modifier.padding(start = 12.dp),
            )
            if (onRotateScreen != null) {
                IconButton(onClick = onRotateScreen, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = NexoIcons.ScreenRotation,
                        contentDescription = stringResource(R.string.reader_rotate_screen),
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** The chapter under [fraction]: the last start at or before it (mirrors the reader's chapterIndexAtFraction). */
private fun previewChapterIndex(
    starts: List<Float?>,
    fraction: Float,
): Int? {
    var found: Int? = null
    starts.forEachIndexed { index, start -> if (start != null && start <= fraction + FRACTION_EPSILON) found = index }
    return found
}

private const val GO_BACK_OFFER_MS = 8_000L
private const val FRACTION_EPSILON = 1e-4f

/** Above this many chapters the notches would merge into a dashed line; skip them. */
private const val MAX_NOTCHES = 60
private val TOUCH_HEIGHT = 32.dp
private val BUBBLE_LIFT = 64.dp

// Reader chrome palette (fixed dark, matching ReaderChrome's footer).
private val FooterBackground = Color(0xFF0D1322)
private val TrackColor = Color(0xFF2F3445)
private val BubbleBackground = Color(0xFF1E2840)
private val TextPrimary = Color(0xFFDDE2F8)
private val TextSecondary = Color(0xFF8A94AD)

@Preview(showBackground = true, backgroundColor = 0xFF0D1322)
@Composable
private fun ReadingProgressBarPreview() {
    NexoTheme(darkTheme = true) {
        ReadingProgressBar(
            progressPercent = 35f,
            chapterTitle = "Primera parte, capítulo tres: el entierro de la madre",
            chapterPagesLeft = 7,
            fallbackLabel = "",
            chapterStarts = listOf(0f, 0.12f, 0.3f, 0.48f, 0.7f, 0.86f),
            canGoBack = true,
            onScrubCommit = {},
            onRotateScreen = {},
        )
    }
}
