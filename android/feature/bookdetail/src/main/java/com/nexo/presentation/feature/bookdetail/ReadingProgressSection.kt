package com.nexo.presentation.feature.bookdetail

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingProgress
import com.nexo.feature.bookdetail.R
import com.nexo.ui.components.atoms.NexoProgressBar
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.nexoColors
import kotlin.math.roundToInt

@Composable
internal fun ReadingProgressSection(
    progress: ReadingProgress?,
    book: Book,
) {
    val percentage = progress?.percentage ?: book.progressPercentage
    // An unopened book has no progress to report; "0% · 0 / 208 pages" is noise.
    if (percentage <= 0f) return

    val colors = nexoColors
    var fillTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(percentage) { fillTarget = (percentage / 100f).coerceIn(0f, 1f) }
    val fill by animateFloatAsState(
        targetValue = fillTarget,
        animationSpec = tween(FillDurationMs, delayMillis = FillDelayMs, easing = FastOutSlowInEasing),
        label = "detailProgress",
    )
    val shape = RoundedCornerShape(NexoDimens.cardCornerRadius + 4.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, colors.borderSubtle, shape),
        shape = shape,
        color = colors.surface,
        contentColor = colors.textSecondary,
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
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.format_percent, (fill * 100).toInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.accentOrange,
                    fontWeight = FontWeight.Bold,
                )
            }

            // NexoProgressBar, not M3's LinearProgressIndicator: the latter draws a
            // "stop" dot at the track's end, which read as a stray mark at 0-5%.
            NexoProgressBar(
                progress = fill,
                height = 8.dp,
                showPercentage = false,
                trackColor = colors.surfaceVariant,
                indicatorColor = colors.accentOrange,
                modifier = Modifier.fillMaxWidth(),
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
                            color = colors.textSecondary,
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
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** Estimated current chapter from progress %, clamped to the chapter count. */
private const val FillDurationMs = 900
private const val FillDelayMs = 250

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
