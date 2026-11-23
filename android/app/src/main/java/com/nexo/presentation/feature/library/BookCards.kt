package com.nexo.presentation.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.nexo.domain.model.Book
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoProgressBar
import com.nexo.ui.components.molecules.BookContextMenuTrigger
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.pressScaleClickable
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

private const val READING_TARGET_MINUTES = 300L

/** Books are portrait: every cover is drawn at 2:3 so none is cropped to a band. */
private const val COVER_ASPECT_RATIO = 2f / 3f

/** Scrim behind the overflow button so it stays legible on any cover artwork. */
private const val MENU_SCRIM_ALPHA = 0.45f

private val GridCoverRadius = 12.dp
private val ListCardRadius = 16.dp
private val ListCoverWidth = 56.dp
private val CompletedBadgeSize = 24.dp

/**
 * Reading progress as a 0..1 fraction: the canonical percent when known,
 * otherwise the legacy time-based estimate.
 */
private fun progressFraction(
    progressPercent: Float?,
    minutesRead: Long,
): Float =
    progressPercent?.let { (it / 100f).coerceIn(0f, 1f) }
        ?: if (minutesRead > 0L) (minutesRead.toFloat() / READING_TARGET_MINUTES).coerceIn(0f, 1f) else 0f

@Composable
fun BookListCard(
    book: Book,
    minutesRead: Long,
    progressPercent: Float? = null,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onMarkCompleted: () -> Unit,
    onMarkPlanToRead: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val fraction = progressFraction(progressPercent, minutesRead)
    val shape = RoundedCornerShape(ListCardRadius)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .pressScaleClickable(shape = shape, onClick = onClick)
                .background(colors.surface)
                .border(1.dp, colors.borderSubtle, shape)
                .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            CoverThumbnail(
                coverPath = book.coverPath,
                contentDescription = stringResource(R.string.library_cover_content_description),
                modifier =
                    Modifier
                        .width(ListCoverWidth)
                        .aspectRatio(COVER_ASPECT_RATIO)
                        .clip(RoundedCornerShape(8.dp)),
            )
            if (fraction >= 1f) CompletedBadge(modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = book.author ?: stringResource(R.string.library_author_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BookProgressLine(fraction = fraction, minutesRead = minutesRead, modifier = Modifier.padding(top = 6.dp))
        }
        BookContextMenuTrigger(
            onEdit = onEdit,
            onMarkCompleted = onMarkCompleted,
            onMarkPlanToRead = onMarkPlanToRead,
            onShare = onShare,
            onDelete = onDelete,
        )
    }
}

/**
 * Grid card: the cover IS the card. A full 2:3 book with its title and author
 * set below it, the way a shelf shows books, instead of a tinted box with the
 * cover cropped into its top half.
 */
@Composable
fun BookGridCard(
    book: Book,
    minutesRead: Long,
    progressPercent: Float? = null,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onMarkCompleted: () -> Unit,
    onMarkPlanToRead: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val fraction = progressFraction(progressPercent, minutesRead)
    val coverShape = RoundedCornerShape(GridCoverRadius)
    Column(
        modifier = modifier.fillMaxWidth().pressScaleClickable(shape = coverShape, onClick = onClick),
    ) {
        Box {
            CoverThumbnail(
                coverPath = book.coverPath,
                contentDescription = stringResource(R.string.library_cover_content_description),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(COVER_ASPECT_RATIO)
                        .clip(coverShape)
                        .border(1.dp, colors.borderSubtle, coverShape),
            )
            if (fraction >= 1f) CompletedBadge(modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            BookContextMenuTrigger(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = MENU_SCRIM_ALPHA)),
                iconTint = Color.White,
                buttonSize = 32.dp,
                iconSize = 18.dp,
                onEdit = onEdit,
                onMarkCompleted = onMarkCompleted,
                onMarkPlanToRead = onMarkPlanToRead,
                onShare = onShare,
                onDelete = onDelete,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = book.author ?: stringResource(R.string.library_author_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BookProgressLine(fraction = fraction, minutesRead = minutesRead, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * Progress for a book that is underway: a slim bar plus the minutes read.
 * Untouched and finished books show nothing here — finished ones carry the
 * check badge on the cover instead.
 */
@Composable
private fun BookProgressLine(
    fraction: Float,
    minutesRead: Long,
    modifier: Modifier = Modifier,
) {
    if (fraction <= 0f || fraction >= 1f) return
    val colors = nexoColors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NexoProgressBar(
            progress = fraction,
            height = 4.dp,
            showPercentage = false,
            trackColor = colors.surfaceVariant,
            indicatorColor = colors.accentOrange,
            modifier = Modifier.weight(1f),
        )
        if (minutesRead > 0L) {
            Text(
                text = stringResource(R.string.library_status_in_progress, minutesRead),
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun CompletedBadge(modifier: Modifier = Modifier) {
    val colors = nexoColors
    Box(
        modifier = modifier.size(CompletedBadgeSize).clip(CircleShape).background(colors.accentGreen),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NexoIcons.Check,
            contentDescription = stringResource(R.string.library_status_completed),
            tint = colors.background,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

private val PreviewBook =
    Book(
        id = "preview-book-1",
        title = "The Hobbit",
        author = "J.R.R. Tolkien",
        coverPath = null,
        filePath = "/preview/the-hobbit.epub",
        format = "epub",
        updatedAtEpochMillis = 0L,
    )

@Preview(showBackground = true)
@Composable
private fun BookCardsDarkPreview() {
    NexoTheme(darkTheme = true) {
        BookListCard(
            book = PreviewBook,
            minutesRead = 60L,
            onClick = {},
            onEdit = {},
            onMarkCompleted = {},
            onMarkPlanToRead = {},
            onShare = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BookCardsLightPreview() {
    NexoTheme(darkTheme = false) {
        BookListCard(
            book = PreviewBook,
            minutesRead = 60L,
            onClick = {},
            onEdit = {},
            onMarkCompleted = {},
            onMarkPlanToRead = {},
            onShare = {},
            onDelete = {},
        )
    }
}
