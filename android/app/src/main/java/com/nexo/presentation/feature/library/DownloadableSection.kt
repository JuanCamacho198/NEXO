package com.nexo.presentation.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.UserBookRow
import com.nexo.presentation.viewmodel.DownloadState
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.molecules.NexoSettingsIcon
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.motion.pressScaleClickable
import com.nexo.ui.motion.rememberEntranceProgress
import com.nexo.ui.motion.staggeredEnter
import com.nexo.ui.theme.nexoColors

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
        Column(
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DownloadableHeader(count = null)
            DownloadableLoadingCard()
        }
        return
    }
    if (books.isEmpty()) return
    val entrance = rememberEntranceProgress()
    Column(
        modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DownloadableHeader(count = books.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(books, key = { _, row -> row.id }) { index, row ->
                DownloadableBookCard(
                    modifier = Modifier.animateItem().staggeredEnter(entrance, index),
                    book = row,
                    isDownloading = downloadStateMap[row.id] is DownloadState.Downloading,
                    onDownload = { if (isDriveAuthorized) pendingDownloadBook = row else onConnectDrive(row) },
                )
            }
        }
    }
    pendingDownloadBook?.let { book ->
        // `author` is declared in another module, so it cannot be smart-cast; bind it
        // to a local val once and branch on that.
        val author = book.author
        NexoDialog(title = stringResource(R.string.download_confirm_title), body = if (author.isNullOrBlank()) stringResource(R.string.download_confirm_body_no_author, book.title) else stringResource(R.string.download_confirm_body, book.title, author), confirmText = stringResource(R.string.book_download), dismissText = stringResource(R.string.action_cancel), onConfirm = {
            onConfirmDownload(book.id)
            pendingDownloadBook = null
        }, onDismiss = { pendingDownloadBook = null }, icon = NexoIcons.CloudDownload)
    }
}

/** Cloud tile + section title, with the count as a quiet pill once it is known. */
@Composable
private fun DownloadableHeader(count: Int?) {
    val colors = nexoColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NexoSettingsIcon(icon = NexoIcons.CloudSync, accent = colors.accentBlue)
        Text(
            text = stringResource(R.string.book_available_from_other_device),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                text = stringResource(R.string.library_count, count),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DownloadableLoadingCard() {
    val colors = nexoColors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.borderSubtle, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.primary)
        Text(
            text = stringResource(R.string.cloud_books_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

/**
 * Same anatomy as a shelf card (2:3 cover, two-line title, author) so remote books
 * read as "your books, elsewhere", with the file size on the cover and one compact
 * download pill underneath instead of a heavy tonal button.
 */
@Composable
private fun DownloadableBookCard(
    book: UserBookRow,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val coverShape = RoundedCornerShape(12.dp)
    Column(modifier = modifier.width(DownloadableCardWidth)) {
        Box {
            CoverThumbnail(
                coverPath = book.coverUrl,
                contentDescription = stringResource(R.string.library_cover_content_description),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(coverShape)
                        .border(1.dp, colors.borderSubtle, coverShape),
            )
            book.fileSize?.takeIf { it > 0 }?.let { bytes ->
                Text(
                    text = formatFileSize(bytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            minLines = 2,
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
        Spacer(modifier = Modifier.height(10.dp))
        DownloadPill(isDownloading = isDownloading, onDownload = onDownload)
    }
}

/** Tinted pill that crossfades into an inline spinner while the file comes down. */
@Composable
private fun DownloadPill(
    isDownloading: Boolean,
    onDownload: () -> Unit,
) {
    val colors = nexoColors
    val shape = RoundedCornerShape(12.dp)
    val container by animateColorAsState(
        targetValue = if (isDownloading) colors.surfaceVariant else colors.primary.copy(alpha = 0.14f),
        animationSpec = tween(220),
        label = "downloadPill",
    )
    Box(
        modifier =
            Modifier
                .then(
                    if (isDownloading) {
                        Modifier.clip(shape)
                    } else {
                        Modifier.pressScaleClickable(shape = shape, onClick = onDownload).semantics { role = Role.Button }
                    },
                ).fillMaxWidth()
                .height(38.dp)
                .background(container),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = isDownloading,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "downloadPillContent",
        ) { downloading ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.primary)
                    Text(
                        text = stringResource(R.string.book_downloading),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                    )
                } else {
                    Icon(imageVector = NexoIcons.CloudDownload, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.book_download),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                }
            }
        }
    }
}

private val DownloadableCardWidth = 128.dp

private const val BYTES_PER_MB = 1024.0 * 1024.0

fun formatFileSize(bytes: Long): String {
    val mb = bytes / BYTES_PER_MB
    return if (mb >= 100) "${mb.toInt()} MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
}
