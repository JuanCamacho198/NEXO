package com.nexo.presentation.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.domain.model.BookStorageItem
import com.nexo.domain.model.CacheUsage
import com.nexo.presentation.screen.library.RemoveBookDialog
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.util.formatFileSize
import com.nexo.presentation.viewmodel.StorageUiState
import com.nexo.ui.components.molecules.NexoSettingsSubPage

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
    RemoveBookDialog(
        bookToDelete = uiState.bookToDelete,
        onDismiss = onDismissDelete,
        onConfirmLocalOnly = onConfirmLocalOnly,
        onConfirmLocalAndDrive = onConfirmLocalAndDrive,
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
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header — total measured live, never a hardcoded number.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text =
                            stringResource(
                                R.string.storage_header_total_used,
                                formatFileSize(context, uiState.totalBytes),
                            ),
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.storage_header_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── Cache breakdown (clear-cache is cache-only) ──────────────
            SectionTitle(text = stringResource(R.string.storage_cache_section))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SizeRow(
                        label = stringResource(R.string.storage_cache_discover),
                        value = formatFileSize(context, uiState.cache.discoverCacheBytes),
                    )
                    SizeRow(
                        label = stringResource(R.string.storage_cache_images),
                        value = formatFileSize(context, uiState.cache.imageCacheBytes),
                    )
                    SizeRow(
                        label = stringResource(R.string.storage_cache_reader),
                        value = formatFileSize(context, uiState.cache.readerCacheBytes),
                    )
                }
            }
            Button(
                onClick = onClearCache,
                enabled = !uiState.isClearingCache,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
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
                Text(text = stringResource(R.string.storage_action_clear_cache))
            }

            // ── Books breakdown (per-book delete is a separate action) ───
            SectionTitle(text = stringResource(R.string.storage_books_section))
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                uiState.books.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.storage_books_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                else ->
                    uiState.books.forEach { book ->
                        BookRow(
                            book = book,
                            sizeLabel = formatFileSize(context, book.sizeBytes),
                            onDelete = { onRequestDeleteBook(book.bookId) },
                        )
                    }
            }

            OutlinedButton(
                onClick = onSweepOrphans,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(text = stringResource(R.string.storage_action_sweep))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun SizeRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BookRow(
    book: BookStorageItem,
    sizeLabel: String,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = sizeLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.storage_action_delete),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
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
