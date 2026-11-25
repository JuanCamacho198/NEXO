package com.nexo.ui.components.molecules

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Bookmark
import com.nexo.presentation.screen.RIBBON_ORANGE
import com.nexo.presentation.screen.RibbonGlyph
import com.nexo.presentation.screen.reader.BookmarkSpots
import com.nexo.presentation.screen.reader.chapterLabel
import com.nexo.ui.components.molecules.reader.ReaderSheetColors
import com.nexo.ui.components.molecules.reader.ReaderSheetHandle
import com.nexo.ui.components.molecules.reader.ReaderSheetShape
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import kotlinx.coroutines.launch

/**
 * The book's bookmarks plus the one action that matters on this page: mark it,
 * or — when it already is — unmark it. That replaces the old one-tap header
 * button, which saved a new bookmark on every press (duplicates included) and
 * gave no way to see or remove them.
 *
 * Marking or unmarking closes the sheet first, so the ribbon dropping onto (or
 * lifting off) the page is what confirms it. Bookmarks are listed in book
 * order; tapping one closes the sheet and jumps there.
 *
 * @param pageBookmark the bookmark on the page being read, or `null`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    pageBookmark: Bookmark?,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
    onBookmarkSelected: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ordered = remember(bookmarks) { BookmarkSpots.inBookOrder(bookmarks) }

    fun closeThen(action: () -> Unit) {
        scope
            .launch { sheetState.hide() }
            .invokeOnCompletion {
                action()
                onDismiss()
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ReaderSheetColors.Surface,
        shape = ReaderSheetShape,
        dragHandle = { ReaderSheetHandle() },
    ) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .then(if (ordered.size > 3) Modifier.fillMaxHeight(0.75f) else Modifier),
        ) {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    text = stringResource(R.string.reader_bookmarks_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ReaderSheetColors.TextPrimary,
                )
                if (ordered.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.reader_bookmarks_count, ordered.size, ordered.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = ReaderSheetColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(16.dp))
                PageBookmarkAction(
                    pageIsBookmarked = pageBookmark != null,
                    onClick = {
                        if (pageBookmark != null) {
                            closeThen { onRemoveBookmark(pageBookmark) }
                        } else {
                            closeThen(onAddBookmark)
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = ReaderSheetColors.Outline)

            if (ordered.isEmpty()) {
                BookmarksEmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(ordered, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            isThisPage = bookmark.id == pageBookmark?.id,
                            onClick = { closeThen { onBookmarkSelected(bookmark) } },
                            onRemove = { onRemoveBookmark(bookmark) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageBookmarkAction(
    pageIsBookmarked: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(shape)
                .then(
                    if (pageIsBookmarked) {
                        Modifier.border(1.dp, ReaderSheetColors.Outline, shape)
                    } else {
                        Modifier.background(RIBBON_ORANGE)
                    },
                ).clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pageIsBookmarked) {
            RibbonGlyph()
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.reader_bookmark_remove_here),
                style = MaterialTheme.typography.labelLarge,
                color = ReaderSheetColors.TextPrimary,
            )
        } else {
            Icon(
                imageVector = NexoIcons.Add,
                contentDescription = null,
                tint = ReaderSheetColors.Surface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.reader_bookmark_add_here),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = ReaderSheetColors.Surface,
            )
        }
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    isThisPage: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val percent = BookmarkSpots.progressPercent(bookmark)
    val pdfPage = BookmarkSpots.spotOf(bookmark).pdfPage
    val whereInBook =
        when {
            pdfPage != null -> stringResource(R.string.reader_bookmark_pdf_page, pdfPage + 1)
            percent != null -> stringResource(R.string.reader_bookmark_book_position, percent)
            else -> null
        }
    val now = System.currentTimeMillis()
    val whenSaved =
        if (now - bookmark.updatedAtEpochMillis < DateUtils.MINUTE_IN_MILLIS) {
            stringResource(R.string.reader_bookmark_just_now)
        } else {
            DateUtils
                .getRelativeTimeSpanString(bookmark.updatedAtEpochMillis, now, DateUtils.MINUTE_IN_MILLIS)
                .toString()
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isThisPage) RIBBON_ORANGE.copy(alpha = 0.10f) else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RibbonGlyph()
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text =
                    bookmark.titleOrSnippet
                        .ifBlank { stringResource(R.string.reader_bookmarks_title) }
                        .let { chapterLabel(it, stringResource(R.string.reader_chapter_short_label)) },
                style = MaterialTheme.typography.bodyLarge,
                color = ReaderSheetColors.TextPrimary,
                fontWeight = if (isThisPage) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = listOfNotNull(whereInBook, whenSaved).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = ReaderSheetColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isThisPage) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.reader_bookmark_this_page),
                        style = MaterialTheme.typography.labelSmall,
                        color = RIBBON_ORANGE,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(RIBBON_ORANGE.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = NexoIcons.Trash,
                contentDescription = stringResource(R.string.reader_bookmark_delete),
                tint = ReaderSheetColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun BookmarksEmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape).background(ReaderSheetColors.SurfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            RibbonGlyph(modifier = Modifier.size(width = 18.dp, height = 27.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.reader_bookmarks_empty),
            style = MaterialTheme.typography.titleSmall,
            color = ReaderSheetColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.reader_bookmarks_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = ReaderSheetColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun previewBookmark(
    id: String,
    title: String,
    position: Int,
    progression: Double,
) = Bookmark(
    id = id,
    bookId = "book",
    cfiLocation = "readium:OEBPS/Text/Section0001.xhtml",
    titleOrSnippet = title,
    updatedAtEpochMillis = System.currentTimeMillis() - 3_600_000L,
    deletedAtEpochMillis = null,
    locatorJson = """{"href":"OEBPS/Text/Section0001.xhtml","locations":{"position":$position,"totalProgression":$progression}}""",
)

@Preview(showBackground = true)
@Composable
private fun BookmarksSheetDarkPreview() {
    val here = previewBookmark("b1", "I", 8, 0.08)
    NexoTheme(darkTheme = true) {
        BookmarksSheet(
            bookmarks = listOf(previewBookmark("b2", "II", 30, 0.31), here),
            pageBookmark = here,
            onAddBookmark = {},
            onRemoveBookmark = {},
            onBookmarkSelected = {},
            onDismiss = {},
        )
    }
}

// Preview-only: fixed dark palette — the reader sheets are always dark.
@Preview(showBackground = true)
@Composable
private fun BookmarksSheetEmptyPreview() {
    NexoTheme(darkTheme = false) {
        BookmarksSheet(
            bookmarks = emptyList(),
            pageBookmark = null,
            onAddBookmark = {},
            onRemoveBookmark = {},
            onBookmarkSelected = {},
            onDismiss = {},
        )
    }
}
