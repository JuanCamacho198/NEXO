package com.nexo.presentation.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexo.R
import com.nexo.data.remote.supabase.UserBookRow
import com.nexo.presentation.viewmodel.DownloadState
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.icons.NexoIcons

@Composable
fun DownloadableBooksSection(
    books: List<UserBookRow>,
    downloadStateMap: Map<String, DownloadState>,
    isDriveAuthorized: Boolean,
    isLoading: Boolean,
    onConnectDrive: (UserBookRow) -> Unit,
    onConfirmDownload: (bookId: String) -> Unit,
) {
    var pendingDownloadBook by remember { mutableStateOf<UserBookRow?>(null) }
    if (isLoading && books.isEmpty()) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Text(text = stringResource(R.string.book_available_from_other_device), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.cloud_books_loading), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (books.isEmpty()) return
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = stringResource(R.string.book_available_from_other_device), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text = stringResource(R.string.library_count, books.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(books, key = { it.id }) { row -> DownloadableBookCard(book = row, isDownloading = downloadStateMap[row.id] is DownloadState.Downloading, onDownload = { if (isDriveAuthorized) pendingDownloadBook = row else onConnectDrive(row) }) }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    pendingDownloadBook?.let { book ->
        NexoDialog(title = stringResource(R.string.download_confirm_title), body = if (book.author.isNullOrBlank()) stringResource(R.string.download_confirm_body_no_author, book.title) else stringResource(R.string.download_confirm_body, book.title, book.author), confirmText = stringResource(R.string.book_download), dismissText = stringResource(R.string.action_cancel), onConfirm = {
            onConfirmDownload(book.id)
            pendingDownloadBook = null
        }, onDismiss = { pendingDownloadBook = null }, icon = NexoIcons.CloudDownload)
    }
}

@Composable
private fun DownloadableBookCard(
    book: UserBookRow,
    isDownloading: Boolean,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.width(140.dp), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))) { CoverThumbnail(coverPath = book.coverUrl, modifier = Modifier.matchParentSize()) }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = book.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (book.author != null) Text(text = book.author, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                book.fileSize?.takeIf { it > 0 }?.let { bytes -> Text(text = formatFileSize(bytes), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (isDownloading) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.book_downloading), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    FilledTonalButton(onClick = onDownload, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(imageVector = NexoIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.book_download), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private const val BYTES_PER_MB = 1024.0 * 1024.0

fun formatFileSize(bytes: Long): String {
    val mb = bytes / BYTES_PER_MB
    return if (mb >= 100) "${mb.toInt()} MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
}
