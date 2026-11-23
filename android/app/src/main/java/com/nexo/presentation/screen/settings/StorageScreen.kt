package com.nexo.presentation.screen.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.BookStorageItem
import com.nexo.domain.model.CacheUsage
import com.nexo.feature.settings.R
import com.nexo.presentation.screen.library.RemoveBookDialog
import com.nexo.presentation.viewmodel.StorageUiState
import com.nexo.ui.components.molecules.NexoSettingsDivider
import com.nexo.ui.components.molecules.NexoSettingsIcon
import com.nexo.ui.components.molecules.NexoSettingsNavRow
import com.nexo.ui.components.molecules.NexoSettingsSection
import com.nexo.ui.components.molecules.NexoSettingsSubPage
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors
import com.nexo.ui.util.formatFileSize

/**
 * Real storage screen (WS6) driven by [StorageUiState].
 *
 * Two independent sections: the cache breakdown with its own clear-cache
 * action, and the per-book footprint with per-book removal behind the shared
 * [RemoveBookDialog]. Sizes are formatted with the locale-aware
 * [formatFileSize]; nothing is hardcoded.
 */
@Composable
fun StorageScreen(
    uiState: StorageUiState,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onRequestDeleteBook: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmLocalOnly: () -> Unit,
    onConfirmLocalAndDrive: () -> Unit,
    onSweepOrphans: () -> Unit = {},
) {
    StorageScreenContent(
        uiState = uiState,
        onBack = onBack,
        onClearCache = onClearCache,
        onRequestDeleteBook = onRequestDeleteBook,
        onSweepOrphans = onSweepOrphans,
    )
    // RemoveBookDialog still lives in :app (it is shared with the library
    // screens), so the copy it renders is supplied from :app's resources.
    RemoveBookDialog(
        bookToDelete = uiState.bookToDelete,
        onDismiss = onDismissDelete,
        onConfirmLocalOnly = onConfirmLocalOnly,
        onConfirmLocalAndDrive = onConfirmLocalAndDrive,
        titleRes = com.nexo.R.string.library_remove_title,
        bodyRes = com.nexo.R.string.library_remove_message,
        confirmRes = com.nexo.R.string.library_remove_continue,
        dismissRes = com.nexo.R.string.library_remove_cancel,
        localOnlyTitleRes = com.nexo.R.string.library_remove_local_only,
        localOnlySubtitleRes = com.nexo.R.string.library_remove_local_only_subtitle,
        localAndDriveTitleRes = com.nexo.R.string.library_remove_local_and_drive,
        localAndDriveSubtitleRes = com.nexo.R.string.library_remove_local_and_drive_subtitle,
        cancelRes = com.nexo.R.string.library_remove_cancel,
    )
}

@Composable
private fun StorageScreenContent(
    uiState: StorageUiState,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onRequestDeleteBook: (String) -> Unit,
    onSweepOrphans: () -> Unit,
) {
    val context = LocalContext.current

    NexoSettingsSubPage(
        title = stringResource(R.string.settings_storage_title),
        onBack = onBack,
    ) {
        val colors = nexoColors
        val booksBytes = uiState.books.sumOf { it.sizeBytes }
        // Fixed category order and colour, never cycled: the bar's segments and the
        // rows below (which act as its legend) always pair the same hue to the same kind.
        val segments =
            listOf(
                StorageSegment(stringResource(R.string.storage_cache_discover), uiState.cache.discoverCacheBytes, colors.primary),
                StorageSegment(stringResource(R.string.storage_cache_images), uiState.cache.imageCacheBytes, colors.accentPurple),
                StorageSegment(stringResource(R.string.storage_cache_reader), uiState.cache.readerCacheBytes, colors.accentOrange),
                StorageSegment(stringResource(R.string.storage_books_section), booksBytes, colors.accentGreen),
            )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Header — total measured live, never a hardcoded number.
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(18.dp))
                        .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text =
                        stringResource(
                            R.string.storage_header_total_used,
                            formatFileSize(context, uiState.totalBytes),
                        ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.textPrimary,
                )
                StorageBar(segments = segments)
                Text(
                    text = stringResource(R.string.storage_header_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }

            // ── Cache breakdown (clear-cache is cache-only) ──────────────
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NexoSettingsSection(title = stringResource(R.string.storage_cache_section)) {
                    segments.take(3).forEachIndexed { index, segment ->
                        if (index > 0) NexoSettingsDivider(inset = false)
                        SizeRow(label = segment.label, value = formatFileSize(context, segment.bytes), dot = segment.color)
                    }
                }
                Button(
                    onClick = onClearCache,
                    enabled = !uiState.isClearingCache,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    // Tonal, not solid red: clearing cache is safe and reversible
                    // (it refills on use), so it should not shout like a delete.
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    if (uiState.isClearingCache) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier
                                    .padding(end = 8.dp)
                                    .size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(text = stringResource(R.string.storage_action_clear_cache), fontWeight = FontWeight.SemiBold)
                }
            }

            // ── Books breakdown (per-book delete is a separate action) ───
            when {
                uiState.isLoading -> {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.storage_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
                uiState.books.isEmpty() -> {
                    NexoSettingsSection(title = stringResource(R.string.storage_books_section)) {
                        NexoSettingsNavRow(
                            label = stringResource(R.string.storage_books_empty),
                            icon = NexoIcons.LibraryBooks,
                            accent = colors.textSecondary,
                            onClick = null,
                        )
                    }
                }
                else ->
                    NexoSettingsSection(title = stringResource(R.string.storage_books_section)) {
                        uiState.books.forEachIndexed { index, book ->
                            if (index > 0) NexoSettingsDivider()
                            BookRow(
                                book = book,
                                sizeLabel = formatFileSize(context, book.sizeBytes),
                                onDelete = { onRequestDeleteBook(book.bookId) },
                            )
                        }
                    }
            }

            OutlinedButton(
                onClick = onSweepOrphans,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(text = stringResource(R.string.storage_action_sweep), fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private data class StorageSegment(
    val label: String,
    val bytes: Long,
    val color: Color,
)

/**
 * Where the space goes, as one horizontal bar of proportional segments with a
 * 2dp surface gap between fills. Empty categories take no width. The rows below
 * name each colour, so the bar needs no legend of its own.
 */
@Composable
private fun StorageBar(segments: List<StorageSegment>) {
    val colors = nexoColors
    val total = segments.sumOf { it.bytes }.coerceAtLeast(1L)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant),
    ) {
        // The filled run grows from the left inside the clipped track.
        Row(
            modifier = Modifier.fillMaxWidth(grow.value).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            segments.filter { it.bytes > 0 }.forEach { segment ->
                Box(
                    modifier =
                        Modifier
                            .weight(segment.bytes.toFloat() / total)
                            .fillMaxHeight()
                            .background(segment.color),
                )
            }
        }
    }
}

@Composable
private fun SizeRow(
    label: String,
    value: String,
    dot: Color,
) {
    val colors = nexoColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dot))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun BookRow(
    book: BookStorageItem,
    sizeLabel: String,
    onDelete: () -> Unit,
) {
    val colors = nexoColors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        NexoSettingsIcon(icon = NexoIcons.Book, accent = colors.accentGreen)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sizeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        TextButton(onClick = onDelete) {
            Text(
                text = stringResource(R.string.storage_action_delete),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StorageScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        StorageScreen(
            uiState = sampleState(),
            onBack = {},
            onClearCache = {},
            onRequestDeleteBook = {},
            onDismissDelete = {},
            onConfirmLocalOnly = {},
            onConfirmLocalAndDrive = {},
            onSweepOrphans = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StorageScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        StorageScreen(
            uiState = sampleState(),
            onBack = {},
            onClearCache = {},
            onRequestDeleteBook = {},
            onDismissDelete = {},
            onConfirmLocalOnly = {},
            onConfirmLocalAndDrive = {},
            onSweepOrphans = {},
        )
    }
}

private fun sampleState() =
    StorageUiState(
        isLoading = false,
        cache =
            CacheUsage(
                discoverCacheBytes = 12L * 1024 * 1024,
                imageCacheBytes = 4L * 1024 * 1024,
                readerCacheBytes = 2L * 1024 * 1024,
            ),
        books =
            listOf(
                BookStorageItem(bookId = "1", title = "La Odisea", sizeBytes = 45L * 1024 * 1024),
                BookStorageItem(bookId = "2", title = "Moby Dick", sizeBytes = 38L * 1024 * 1024),
            ),
    )
