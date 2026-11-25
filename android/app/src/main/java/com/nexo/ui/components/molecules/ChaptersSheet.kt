package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.viewmodel.reader.BookChapter
import com.nexo.presentation.viewmodel.reader.cleanChapterTitle
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import com.nexo.ui.components.molecules.reader.ReaderSheetHandle
import com.nexo.ui.components.molecules.reader.ReaderSheetShape
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Rows kept above the current chapter when the sheet opens, so it lands in context, not at the top edge. */
private const val ROWS_ABOVE_CURRENT = 2

/**
 * The book's table of contents, opened already scrolled to the chapter being
 * read. Chapters already passed are dimmed, the current one is marked with how
 * far into it the reader is, and each entry shows where in the book it starts.
 *
 * Tapping a row jumps there ([onChapterSelected] receives the LIST POSITION,
 * not the spine index — TOC and spine sizes differ) and closes the sheet with
 * its own hide animation.
 *
 * @param progressPercent whole-book progress, 0..100.
 * @param chapterStartFractions each chapter's start as a 0..1 fraction of the
 *   book, aligned with [chapters]; empty until Readium has computed positions,
 *   in which case starts and in-chapter progress are simply not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    chapters: List<BookChapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    progressPercent: Float = 0f,
    chapterStartFractions: List<Float?> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState =
        rememberLazyListState(initialFirstVisibleItemIndex = (currentChapterIndex - ROWS_ABOVE_CURRENT).coerceAtLeast(0))
    val currentFraction =
        remember(progressPercent, chapterStartFractions, currentChapterIndex) {
            chapterReadingFraction(progressPercent, chapterStartFractions, currentChapterIndex)
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ReaderSheetColors.Surface,
        shape = ReaderSheetShape,
        dragHandle = { ReaderSheetHandle() },
    ) {
        Column(modifier = modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = stringResource(R.string.reader_toc),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ReaderSheetColors.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.reader_toc_book_progress, progressPercent.roundToInt().coerceIn(0, 100)),
                    style = MaterialTheme.typography.bodySmall,
                    color = ReaderSheetColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                ProgressTrack(fraction = progressPercent / 100f, height = 4)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = ReaderSheetColors.Outline)

            if (chapters.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.reader_toc_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ReaderSheetColors.TextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(chapters, key = { position, chapter -> "${chapter.id}#$position" }) { position, chapter ->
                        ChapterRow(
                            title =
                                cleanChapterTitle(chapter.title)
                                    ?: stringResource(R.string.reader_chapter_fallback, position + 1),
                            depth = chapter.depth,
                            status =
                                when {
                                    position == currentChapterIndex -> ChapterStatus.CURRENT
                                    position < currentChapterIndex -> ChapterStatus.READ
                                    else -> ChapterStatus.UPCOMING
                                },
                            startPercent = chapterStartFractions.getOrNull(position)?.let { (it * 100).roundToInt() },
                            currentFraction = if (position == currentChapterIndex) currentFraction else null,
                            onClick = {
                                onChapterSelected(position)
                                scope
                                    .launch { sheetState.hide() }
                                    .invokeOnCompletion { onDismiss() }
                            },
                        )
                    }
                }
            }
        }
    }
}

private enum class ChapterStatus { READ, CURRENT, UPCOMING }

@Composable
private fun ChapterRow(
    title: String,
    depth: Int,
    status: ChapterStatus,
    startPercent: Int?,
    currentFraction: Float?,
    onClick: () -> Unit,
) {
    val isCurrent = status == ChapterStatus.CURRENT
    val readingNow = stringResource(R.string.reader_toc_reading_now)
    val titleColor =
        when (status) {
            ChapterStatus.CURRENT -> ReaderSheetColors.Accent
            ChapterStatus.READ -> ReaderSheetColors.TextSecondary
            ChapterStatus.UPCOMING -> ReaderSheetColors.TextPrimary
        }
    val indent = (depth * 16).coerceAtMost(48).dp

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isCurrent) ReaderSheetColors.Accent.copy(alpha = 0.12f) else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .then(if (isCurrent) Modifier.semantics { stateDescription = readingNow } else Modifier)
                .padding(start = 12.dp + indent, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .padding(end = 12.dp)
                    .size(width = 3.dp, height = 22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isCurrent) ReaderSheetColors.Accent else Color.Transparent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (depth > 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = titleColor,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCurrent) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = NexoIcons.ReaderBookmark,
                        contentDescription = null,
                        tint = ReaderSheetColors.Accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = readingNow,
                        style = MaterialTheme.typography.labelSmall,
                        color = ReaderSheetColors.Accent,
                    )
                    if (currentFraction != null) {
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.width(72.dp)) { ProgressTrack(fraction = currentFraction, height = 3) }
                    }
                }
            }
        }
        if (startPercent != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.reader_toc_start_percent, startPercent),
                style = MaterialTheme.typography.labelMedium,
                color = ReaderSheetColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ProgressTrack(
    fraction: Float,
    height: Int,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(height.dp))
                .background(ReaderSheetColors.Outline),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(height.dp))
                    .background(ReaderSheetColors.Accent),
        )
    }
}

/**
 * How far (0..1) the reader is through the chapter at [current], from the
 * whole-book [progressPercent] and each chapter's start. The chapter ends where
 * the next LATER start begins — a part and its first sub-chapter share a start,
 * so equal starts are skipped. `null` when the chapter's start is unknown.
 */
internal fun chapterReadingFraction(
    progressPercent: Float,
    chapterStarts: List<Float?>,
    current: Int,
): Float? {
    val start = chapterStarts.getOrNull(current) ?: return null
    val end = chapterStarts.drop(current + 1).firstOrNull { it != null && it > start } ?: 1f
    if (end <= start) return null
    return ((progressPercent / 100f - start) / (end - start)).coerceIn(0f, 1f)
}

private val previewChapters =
    listOf(
        BookChapter(index = 0, id = "c0", title = "Primera parte", href = "ch1.xhtml", depth = 0),
        BookChapter(index = 0, id = "c0a", title = "I", href = "ch1.xhtml#s1", depth = 1),
        BookChapter(index = 1, id = "c1", title = "II", href = "ch2.xhtml", depth = 1),
        BookChapter(index = 2, id = "c2", title = "III", href = "ch3.xhtml", depth = 1),
        BookChapter(index = 3, id = "c3", title = "Segunda parte", href = "ch4.xhtml", depth = 0),
        BookChapter(index = 3, id = "c3a", title = "I", href = "ch4.xhtml#s1", depth = 1),
    )

@Preview(showBackground = true)
@Composable
private fun ChaptersSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        ChaptersSheet(
            chapters = previewChapters,
            currentChapterIndex = 2,
            onChapterSelected = {},
            onDismiss = {},
            progressPercent = 41f,
            chapterStartFractions = listOf(0f, 0f, 0.18f, 0.36f, 0.55f, 0.55f),
        )
    }
}

// Preview-only: fixed dark palette — the reader sheets are always dark.
@Preview(showBackground = true)
@Composable
private fun ChaptersSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        ChaptersSheet(
            chapters = previewChapters,
            currentChapterIndex = 0,
            onChapterSelected = {},
            onDismiss = {},
        )
    }
}
