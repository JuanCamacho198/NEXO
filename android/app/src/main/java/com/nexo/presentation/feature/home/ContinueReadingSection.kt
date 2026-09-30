package com.nexo.presentation.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoProgressBar
import com.nexo.ui.components.molecules.BookContextMenuTrigger

@Composable
fun ContinueReadingSection(
    books: List<Book>,
    progressPercentByBook: Map<String, Float> = emptyMap(),
    onBookSelected: (String, String, String) -> Unit,
    onContinueReading: (String, String?, String) -> Unit,
    onEdit: (Book) -> Unit = {},
    onMarkCompleted: (Book) -> Unit = {},
    onMarkPlanToRead: (Book) -> Unit = {},
    onShare: (Book) -> Unit = {},
    onDelete: (Book) -> Unit = {},
) {
    Column {
        Text(text = stringResource(R.string.home_continue_reading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        if (books.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(books, key = { it.id }) { book ->
                    val canonicalProgress = progressPercentByBook[book.id] ?: 0f
                    ContinueReadingCard(book = book, progressFraction = (canonicalProgress / 100f).coerceIn(0f, 1f), onBookSelected = onBookSelected, onContinueReading = onContinueReading, onEdit = onEdit, onMarkCompleted = onMarkCompleted, onMarkPlanToRead = onMarkPlanToRead, onShare = onShare, onDelete = onDelete)
                }
            }
        } else {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(NexoDimens.spacingSm), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(text = stringResource(R.string.home_no_current_book), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(NexoDimens.spacingMd))
            }
        }
    }
}

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
    Surface(modifier = Modifier.width(240.dp).clickable { onBookSelected(book.id, book.filePath, book.format) }, shape = RoundedCornerShape(NexoDimens.spacingSm), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
        Row(modifier = Modifier.padding(NexoDimens.spacingMd)) {
            CoverThumbnail(coverPath = book.coverPath, modifier = Modifier.width(80.dp).height(120.dp).clip(RoundedCornerShape(NexoDimens.spacingXs)))
            Spacer(modifier = Modifier.width(NexoDimens.spacingMd))
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // Shared shelf menu; the carousel omits "Plan to read" and keeps
                    // delete explicit (never aliased to long-press). DropdownMenu renders
                    // in a popup window, so no LazyRow clipping.
                    BookContextMenuTrigger(
                        buttonSize = 32.dp,
                        showPlanToRead = false,
                        onEdit = { onEdit(book) },
                        onMarkCompleted = { onMarkCompleted(book) },
                        onMarkPlanToRead = { onMarkPlanToRead(book) },
                        onShare = { onShare(book) },
                        onDelete = { onDelete(book) },
                    )
                }
                book.author?.let { author -> Text(text = author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
                NexoProgressBar(progress = progressFraction, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
                NexoButton(onClick = { onContinueReading(book.id, book.filePath, book.format) }, variant = NexoButtonVariant.FILLED, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp), modifier = Modifier.height(36.dp)) { Text(text = stringResource(R.string.home_continuar), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
