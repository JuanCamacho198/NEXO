package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.reader.BookChapter

/**
 * Modal bottom sheet that lists the book's chapters as a
 * scrollable list. Tapping a row jumps to that chapter via
 * [onChapterSelected] and auto-dismisses the sheet. The current
 * chapter is highlighted with a left dot, a colored text, and a
 * darker row background.
 *
 * Visual design is locked to the dark reader theme (background
 * `#161F33`, text `#DDE2F8`, accent `#ADC6FF`) — this sheet is
 * intended to be shown on top of the dark reading surface.
 *
 * @param chapters Ordered list of chapters. When empty, an
 *   `R.string.highlights_empty` placeholder is shown.
 * @param currentChapterIndex Index of the chapter the reader is
 *   currently in. Used to highlight the active row. Out-of-range
 *   values simply produce no highlight.
 * @param onChapterSelected Invoked with the chosen index when the
 *   user taps a row. The sheet auto-dismisses immediately after.
 * @param onDismiss Invoked on swipe-down, scrim-tap, or back-press.
 * @param modifier Modifier applied to the inner `Column`.
 *
 * **Visual**: dark-themed `ModalBottomSheet` with 24dp top corners.
 *   Drag handle, `titleLarge` bold header, `HorizontalDivider`,
 *   then a 420dp `LazyColumn` of `ChapterRow`s. Each row: 8dp dot
 *   (current only) + title, 2-line ellipsized. Nested sub-chapters
 *   are indented by their [BookChapter.depth] (20dp per level, capped
 *   at 80dp) and rendered in `bodySmall`. Current row: blue text
 *   (`#ADC6FF`), semibold, `#2F3445` background.
 * **Behavior**: tap a row → [onChapterSelected(listPosition)] + sheet
 *   dismissal. The index passed is the list position (0..chapters.size-1),
 *   NOT the spine index — this avoids the +3 offset when TOC size (28)
 *   differs from spine size (31). No internal state. The list height is
 *   hard-capped at 420dp — if the book has more chapters, the list scrolls.
 * **Recomposition**: recomposes when `chapters`, `currentChapterIndex`,
 *   or callbacks change. New `LazyColumn` items are keyed implicitly
 *   by their position in the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    chapters: List<BookChapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161F33),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
        ) {
            // ── Drag Handle ────────────────────────────────────────
            Box(
                modifier =
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF4A5568))
                        .align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Header ─────────────────────────────────────────────
            Text(
                text = stringResource(R.string.reader_chapters_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFDDE2F8),
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(color = Color(0xFF2F3445))

            Spacer(modifier = Modifier.height(8.dp))

            // ── Chapters List ──────────────────────────────────────
            if (chapters.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.highlights_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF718096),
                    )
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(chapters) { listPosition, chapter ->
                        ChapterRow(
                            title = chapter.title,
                            depth = chapter.depth,
                            isCurrent = listPosition == currentChapterIndex,
                            onClick = {
                                onChapterSelected(listPosition)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    title: String,
    depth: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleColor = if (isCurrent) Color(0xFFADC6FF) else Color(0xFFDDE2F8)
    val weight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
    val rowBg = if (isCurrent) Color(0xFF2F3445) else Color.Transparent
    // Indent nested sub-chapters: each level adds 20dp, capped so deep
    // hierarchies don't squeeze the text off-screen.
    val indent = (depth * 20).coerceAtMost(80).dp

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(rowBg)
                .clickable(onClick = onClick)
                .padding(start = 14.dp + indent, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Current chapter indicator dot
        Box(
            modifier =
                Modifier
                    .padding(end = 12.dp)
                    .size(if (isCurrent) 8.dp else 0.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) Color(0xFFADC6FF) else Color.Transparent),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { stringResource(R.string.reader_chapter_fallback, depth + 1) },
                style =
                    if (depth > 0) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                color = titleColor,
                fontWeight = weight,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ChaptersSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        ChaptersSheet(
            chapters =
                listOf(
                    BookChapter(index = 0, id = "c0", title = "Capítulo 1", href = "ch1.xhtml", depth = 0),
                    BookChapter(index = 0, id = "c0a", title = "Sección 1.1", href = "ch1.xhtml#s1", depth = 1),
                    BookChapter(index = 0, id = "c0b", title = "Sección 1.1.1", href = "ch1.xhtml#s1a", depth = 2),
                    BookChapter(index = 1, id = "c1", title = "Capítulo 2", href = "ch2.xhtml", depth = 0),
                    BookChapter(index = 2, id = "c2", title = "Capítulo 3", href = "ch3.xhtml", depth = 0),
                ),
            currentChapterIndex = 1,
            onChapterSelected = {},
            onDismiss = {},
        )
    }
}

// Preview-only: fixed dark palette — light render is intentionally broken (see sdd/ui-previews-both-themes spec R7; color migration deferred)
@Preview(showBackground = true)
@Composable
private fun ChaptersSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        ChaptersSheet(
            chapters =
                listOf(
                    BookChapter(index = 0, id = "c0", title = "Capítulo 1", href = "ch1.xhtml", depth = 0),
                    BookChapter(index = 0, id = "c0a", title = "Sección 1.1", href = "ch1.xhtml#s1", depth = 1),
                    BookChapter(index = 1, id = "c1", title = "Capítulo 2", href = "ch2.xhtml", depth = 0),
                    BookChapter(index = 2, id = "c2", title = "Capítulo 3", href = "ch3.xhtml", depth = 0),
                ),
            currentChapterIndex = 1,
            onChapterSelected = {},
            onDismiss = {},
        )
    }
}
