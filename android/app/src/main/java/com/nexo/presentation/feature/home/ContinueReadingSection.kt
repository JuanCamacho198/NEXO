package com.nexo.presentation.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoProgressBar
import com.nexo.ui.components.molecules.BookContextMenuTrigger
import com.nexo.ui.icons.NexoIcons

/** Card width in the carousel; wide enough for the cover plus the meta column. */
private val CardWidth = 300.dp

/** Cover dimensions in the card, matching the `.pen` `cover` (100x148). */
private val CoverWidth = 88.dp
private val CoverHeight = 130.dp

/** Diameter of the circular amber play affordance (`.pen` `play_btn` 42x42). */
private val PlayButtonSize = 42.dp

/** Corner radius of the card and its cover, from the `.pen` (20 / 12). */
private val CardRadius = 20.dp
private val CoverRadius = 12.dp

/**
 * Continue-reading section for the populated Home.
 *
 * A horizontal carousel, matching the `.pen`: one card per in-progress book,
 * scrolled sideways, with the section heading above it.
 */
@Composable
fun ContinueReadingSection(
    books: List<Book>,
    progressPercentByBook: Map<String, Float> = emptyMap(),
    onBookSelected: (String, String, String) -> Unit,
    onContinueReading: (String, String?, String) -> Unit,
    onViewAll: () -> Unit = {},
    onEdit: (Book) -> Unit = {},
    onMarkCompleted: (Book) -> Unit = {},
    onMarkPlanToRead: (Book) -> Unit = {},
    onShare: (Book) -> Unit = {},
    onDelete: (Book) -> Unit = {},
) {
    Column {
        ContinueSectionHeader(onViewAll = onViewAll)
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        if (books.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
                items(books, key = { it.id }) { book ->
                    val canonicalProgress = progressPercentByBook[book.id] ?: 0f
                    ContinueReadingCard(book = book, progressFraction = (canonicalProgress / 100f).coerceIn(0f, 1f), onBookSelected = onBookSelected, onContinueReading = onContinueReading, onEdit = onEdit, onMarkCompleted = onMarkCompleted, onMarkPlanToRead = onMarkPlanToRead, onShare = onShare, onDelete = onDelete)
                }
            }
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(NexoDimens.spacingSm))
                        .background(NexoColors.surfaceVariant)
                        .padding(NexoDimens.spacingMd),
            ) {
                Text(text = stringResource(R.string.home_no_current_book), style = MaterialTheme.typography.bodyMedium, color = NexoColors.textSecondary)
            }
        }
    }
}

/**
 * Section heading: the label on the left, an amber "Ver todo" link on the right.
 *
 * Mirrors the `.pen` `section_header` — the link is the accent colour with a
 * chevron, matching "Mi biblioteca" and the genres header below it.
 */
@Composable
private fun ContinueSectionHeader(onViewAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.home_continue_reading),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = NexoColors.textPrimary,
        )
        Row(
            modifier = Modifier.clickable(onClick = onViewAll).padding(NexoDimens.spacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.home_ver_todo),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = NexoColors.accentOrange,
            )
            Icon(
                imageVector = NexoIcons.ChevronRight,
                contentDescription = null,
                tint = NexoColors.accentOrange,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun LastReadText(
    updatedAtEpochMillis: Long?,
    modifier: Modifier = Modifier,
) {
    val label = LastReadLabel.from(updatedAtEpochMillis, System.currentTimeMillis()) ?: return
    val arg = label.formatArg()
    val text =
        if (arg == null) {
            stringResource(label.stringRes())
        } else {
            stringResource(label.stringRes(), arg)
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = NexoColors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * One continue-reading card.
 *
 * The surface is the design's radial tint — [NexoColors.backgroundBlueTransparent]
 * at the centre fading to [NexoColors.surfaceVariant] at the edge — with a
 * [NexoColors.accentBlue]-derived hairline. The cover keeps the book's own
 * artwork; the meta column carries title, author, progress, chapter and the
 * last-read stamp.
 */
@Composable
fun ContinueReadingCard(
    book: Book,
    progressFraction: Float = 0f,
    onBookSelected: (String, String, String) -> Unit,
    onContinueReading: (String, String?, String) -> Unit,
    onEdit: (Book) -> Unit = {},
    onMarkCompleted: (Book) -> Unit = {},
    onMarkPlanToRead: (Book) -> Unit = {},
    onShare: (Book) -> Unit = {},
    onDelete: (Book) -> Unit = {},
) {
    Box(
        modifier =
            Modifier
                .width(CardWidth)
                .clip(RoundedCornerShape(CardRadius))
                .background(
                    Brush.radialGradient(
                        colors = listOf(NexoColors.backgroundBlueTransparent, NexoColors.surfaceVariant),
                    ),
                ).border(1.dp, ContinueCardStroke, RoundedCornerShape(CardRadius))
                .clickable { onBookSelected(book.id, book.filePath, book.format) }
                .padding(NexoDimens.spacingMd),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd)) {
            CoverThumbnail(
                coverPath = book.coverPath,
                modifier = Modifier.width(CoverWidth).height(CoverHeight).clip(RoundedCornerShape(CoverRadius)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NexoColors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Shared shelf menu; the carousel omits "Plan to read" and keeps
                    // delete explicit (never aliased to long-press). DropdownMenu renders
                    // in a popup window, so no LazyRow clipping.
                    BookContextMenuTrigger(
                        buttonSize = 28.dp,
                        showPlanToRead = false,
                        onEdit = { onEdit(book) },
                        onMarkCompleted = { onMarkCompleted(book) },
                        onMarkPlanToRead = { onMarkPlanToRead(book) },
                        onShare = { onShare(book) },
                        onDelete = { onDelete(book) },
                    )
                }
                book.author?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelMedium,
                        color = NexoColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
                    NexoProgressBar(
                        progress = progressFraction,
                        height = 8.dp,
                        showPercentage = false,
                        trackColor = NexoColors.background,
                        indicatorColor = NexoColors.accentOrange,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.home_progress_percent, (progressFraction * 100).toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = NexoColors.textPrimary,
                    )
                }
                Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
                ChapterRow(book = book)
                Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LastReadText(updatedAtEpochMillis = book.progressUpdatedAtEpochMillis)
                    PlayButton(onClick = { onContinueReading(book.id, book.filePath, book.format) })
                }
            }
        }
    }
}

/** Amber hairline around the card (`.pen` stroke `#14489c`). */
private val ContinueCardStroke =
    androidx.compose.ui.graphics
        .Color(0xFF14489C)

/**
 * Chapter line: a book glyph plus the chapter label.
 *
 * Falls back to the author-less layout when the book carries no chapter data,
 * rather than rendering an empty row.
 */
@Composable
private fun ChapterRow(book: Book) {
    val chapterCount = book.chapterCount
    if (chapterCount == null || chapterCount <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = NexoIcons.BookOpen,
            contentDescription = null,
            tint = NexoColors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.home_chapter_count, chapterCount),
            style = MaterialTheme.typography.labelSmall,
            color = NexoColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Circular play affordance that resumes the book (`.pen` `play_btn`).
 *
 * Outlined, not filled: the `.pen` draws a `$accent` ring with an `$accent`
 * glyph, so the amber stays an outline against the card's blue tint.
 */
@Composable
private fun PlayButton(onClick: () -> Unit) {
    val label = stringResource(R.string.home_continuar)
    Box(
        modifier =
            Modifier
                .size(PlayButtonSize)
                .clip(CircleShape)
                .border(1.5.dp, NexoColors.accentOrange, CircleShape)
                .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NexoIcons.Play,
            contentDescription = label,
            tint = NexoColors.accentOrange,
            modifier = Modifier.size(20.dp),
        )
    }
}
