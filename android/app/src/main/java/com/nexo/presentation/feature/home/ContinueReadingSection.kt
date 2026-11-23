package com.nexo.presentation.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoProgressBar
import com.nexo.ui.components.molecules.BookContextMenuTrigger
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.pressScaleClickable
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.nexoColors

/** Cover dimensions in the card: a 2:3 book, tall enough to anchor the card. */
private val CoverWidth = 84.dp
private val CoverHeight = 126.dp

/** Diameter of the filled amber play button; a full 48dp touch target. */
private val PlayButtonSize = 48.dp

private val CardRadius = 20.dp
private val CoverRadius = 10.dp

/** Share of the row a card takes when there are several, so the next one peeks in. */
private const val MultiCardWidthFraction = 0.86f

/** Progress fill animation; starts after the section's own entrance has begun. */
private const val ProgressFillDurationMs = 900
private const val ProgressFillDelayMs = 300

private val EmptyIconSize = 44.dp

/**
 * Continue-reading section for the populated Home.
 *
 * A single in-progress book gets the full width; several become a carousel in
 * which the next card peeks in from the edge, which is what tells the reader the
 * row scrolls.
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
        HomeSectionHeader(title = stringResource(R.string.home_continue_reading), onAction = onViewAll)
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        if (books.isNotEmpty()) {
            val widthFraction = if (books.size == 1) 1f else MultiCardWidthFraction
            LazyRow(horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
                items(books, key = { it.id }) { book ->
                    val canonicalProgress = progressPercentByBook[book.id] ?: 0f
                    ContinueReadingCard(book = book, progressFraction = (canonicalProgress / 100f).coerceIn(0f, 1f), onBookSelected = onBookSelected, onContinueReading = onContinueReading, onEdit = onEdit, onMarkCompleted = onMarkCompleted, onMarkPlanToRead = onMarkPlanToRead, onShare = onShare, onDelete = onDelete, modifier = Modifier.fillParentMaxWidth(widthFraction))
                }
            }
        } else {
            NoCurrentBookCard(onClick = onViewAll)
        }
    }
}

/**
 * Empty state of the section: instead of a dead grey bar, a tappable row that
 * takes the reader to their library to pick the next book.
 */
@Composable
private fun NoCurrentBookCard(onClick: () -> Unit) {
    val colors = nexoColors
    val label = stringResource(R.string.home_ver_todo)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .pressScaleClickable(shape = RoundedCornerShape(CardRadius), onClickLabel = label, onClick = onClick)
                .background(colors.surface)
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(CardRadius))
                .padding(NexoDimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd),
    ) {
        Box(
            modifier = Modifier.size(EmptyIconSize).clip(CircleShape).background(colors.backgroundYellowTransparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = NexoIcons.BookOpen, contentDescription = null, tint = colors.accentOrange, modifier = Modifier.size(22.dp))
        }
        Text(
            text = stringResource(R.string.home_no_current_book),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(imageVector = NexoIcons.ChevronRight, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun LastReadText(
    updatedAtEpochMillis: Long?,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
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
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * One continue-reading card.
 *
 * The surface is the design's radial tint — the theme-aware
 * `backgroundBlueTransparent` at the centre fading to `surfaceVariant` at the
 * edge — with an `accentBlue`-derived hairline. The cover keeps the book's own
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
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    var progressTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(progressFraction) { progressTarget = progressFraction }
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget,
        animationSpec = tween(ProgressFillDurationMs, delayMillis = ProgressFillDelayMs, easing = FastOutSlowInEasing),
        label = "continueProgress",
    )
    Box(
        modifier =
            modifier
                .pressScaleClickable(shape = RoundedCornerShape(CardRadius)) { onBookSelected(book.id, book.filePath, book.format) }
                .background(
                    Brush.linearGradient(
                        colors = listOf(colors.surfaceVariant, colors.surface),
                    ),
                ).border(1.dp, colors.borderSubtle, RoundedCornerShape(CardRadius))
                .padding(NexoDimens.spacingMd),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd)) {
            CoverThumbnail(
                coverPath = book.coverPath,
                contentDescription = stringResource(R.string.library_cover_content_description),
                modifier = Modifier.width(CoverWidth).height(CoverHeight).clip(RoundedCornerShape(CoverRadius)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
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
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
                    NexoProgressBar(
                        progress = animatedProgress,
                        height = 6.dp,
                        showPercentage = false,
                        trackColor = colors.background,
                        indicatorColor = colors.accentOrange,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.home_progress_percent, (animatedProgress * 100).toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
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

/**
 * Chapter line: a book glyph plus the chapter label.
 *
 * Falls back to the author-less layout when the book carries no chapter data,
 * rather than rendering an empty row.
 */
@Composable
private fun ChapterRow(book: Book) {
    val colors = nexoColors
    val chapterCount = book.chapterCount
    if (chapterCount == null || chapterCount <= 0) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = NexoIcons.BookOpen,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(R.string.home_chapter_count, chapterCount),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Filled amber play button that resumes the book.
 *
 * Filled rather than outlined: resuming is the single most likely action on
 * the whole Home, so it carries the screen's one solid accent.
 */
@Composable
private fun PlayButton(onClick: () -> Unit) {
    val colors = nexoColors
    val label = stringResource(R.string.home_continuar)
    Box(
        modifier =
            Modifier
                .size(PlayButtonSize)
                .pressScaleClickable(shape = RoundedCornerShape(16.dp), onClickLabel = label, onClick = onClick)
                .background(colors.accentOrange),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NexoIcons.Play,
            contentDescription = label,
            tint = colors.onAccentOrange,
            modifier = Modifier.size(22.dp),
        )
    }
}
