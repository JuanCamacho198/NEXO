package com.nexo.presentation.feature.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingProgress
import com.nexo.presentation.theme.NexoDimens
import kotlin.math.roundToInt

@Composable
internal fun ReadingProgressSection(
    progress: ReadingProgress?,
    book: Book,
) {
    val percentage = progress?.percentage ?: book.progressPercentage

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NexoDimens.cardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.book_detail_progress_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.format_percent, percentage.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            LinearProgressIndicator(
                progress = { (percentage / 100f).coerceIn(0f, 1f) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(9999.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline,
            )

            if (book.chapterCount != null || book.totalPages != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    book.chapterCount?.let { chapters ->
                        Text(
                            text =
                                stringResource(
                                    R.string.book_detail_chapter_x_of_y,
                                    estimatedChapter(percentage, chapters),
                                    chapters,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    book.totalPages?.let { total ->
                        Text(
                            text =
                                stringResource(
                                    R.string.book_detail_pages_of,
                                    estimatedCurrentPage(progress, total),
                                    total,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Estimated current chapter from progress %, clamped to the chapter count. */
private fun estimatedChapter(
    percentage: Float,
    chapterCount: Int,
): Int = (percentage / 100f * chapterCount).roundToInt().coerceIn(1, chapterCount)

/** Real current page when the reader reports one; else estimated from progress %. */
private fun estimatedCurrentPage(
    progress: ReadingProgress?,
    totalPages: Int,
): Int {
    progress?.currentPage?.takeIf { it > 0 }?.let { return it }
    val percentage = progress?.percentage ?: 0f
    return (percentage / 100f * totalPages).roundToInt().coerceIn(0, totalPages)
}
