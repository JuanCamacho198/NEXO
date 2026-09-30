package com.nexo.presentation.screen.library

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoProgressBar
import com.nexo.ui.components.molecules.BookContextMenuTrigger

private const val READING_TARGET_MINUTES = 300L
private const val SURFACE_ALPHA = 0.3f

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
) {
    // Canonical progress via GetBookProgressUseCase.observeProgressPercent (distinctUntilChanged) — wins over time-derived fallback
    val canonicalFraction = progressPercent?.let { (it / 100f).coerceIn(0f, 1f) }
    val progressFraction =
        canonicalFraction ?: if (minutesRead > 0L) {
            (minutesRead.toFloat() / READING_TARGET_MINUTES).coerceIn(0f, 1f)
        } else {
            0f
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SURFACE_ALPHA),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            CoverThumbnail(
                coverPath = book.coverPath,
                modifier =
                    Modifier
                        .width(60.dp)
                        .height(80.dp)
                        .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.author ?: stringResource(R.string.library_author_unknown),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progressFraction > 0f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    NexoProgressBar(
                        progress = progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.library_status_in_progress, minutesRead),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
}

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
) {
    // Canonical progress via GetBookProgressUseCase.observeProgressPercent (distinctUntilChanged) — wins over time-derived fallback
    val canonicalFraction = progressPercent?.let { (it / 100f).coerceIn(0f, 1f) }
    val progressFraction =
        canonicalFraction ?: if (minutesRead > 0L) {
            (minutesRead.toFloat() / READING_TARGET_MINUTES).coerceIn(0f, 1f)
        } else {
            0f
        }

    val statusText =
        when {
            progressFraction >= 1f -> stringResource(R.string.library_status_completed)
            progressPercent != null && progressFraction > 0f -> stringResource(R.string.library_status_in_progress, minutesRead)
            minutesRead > 0L -> stringResource(R.string.library_status_in_progress, minutesRead)
            else -> null
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SURFACE_ALPHA))
                .clickable(onClick = onClick),
    ) {
        Box {
            CoverThumbnail(
                coverPath = book.coverPath,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            )
            BookContextMenuTrigger(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                iconTint = MaterialTheme.colorScheme.onSurface,
                onEdit = onEdit,
                onMarkCompleted = onMarkCompleted,
                onMarkPlanToRead = onMarkPlanToRead,
                onShare = onShare,
                onDelete = onDelete,
            )
        }

        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = book.title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = book.author ?: stringResource(R.string.library_author_unknown),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (statusText != null) {
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )

                if (progressFraction in 0.01f..0.99f) {
                    Spacer(modifier = Modifier.height(2.dp))
                    NexoProgressBar(
                        progress = progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                        height = 2.dp,
                    )
                }
            }
        }
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
