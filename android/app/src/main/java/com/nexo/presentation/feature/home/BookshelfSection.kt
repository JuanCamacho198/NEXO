package com.nexo.presentation.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoEmptyState

@Composable
fun MyBookshelfSection(
    books: List<Book>,
    onViewAll: () -> Unit,
    onBookSelected: (String, String, String) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.home_my_bookshelf_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            NexoButton(onClick = onViewAll, variant = NexoButtonVariant.TEXT) { Text(text = stringResource(R.string.home_ver_todo)) }
        }
        if (books.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingSm)) {
                items(books, key = { it.id }) { book -> BookshelfCard(book = book, onClick = { onBookSelected(book.id, book.filePath, book.format) }) }
            }
        } else {
            NexoEmptyState(icon = ImageVector.vectorResource(R.drawable.ic_empty_library), title = stringResource(R.string.home_bookshelf_empty_title), subtitle = stringResource(R.string.home_bookshelf_empty_subtitle), modifier = Modifier.fillMaxWidth().padding(vertical = NexoDimens.spacingMd))
        }
    }
}

@Composable
fun BookshelfCard(
    book: Book,
    onClick: () -> Unit,
) {
    Surface(modifier = Modifier.width(120.dp).clickable(onClick = onClick), shape = RoundedCornerShape(NexoDimens.spacingSm), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
        Column(modifier = Modifier.padding(NexoDimens.spacingSm), horizontalAlignment = Alignment.CenterHorizontally) {
            CoverThumbnail(coverPath = book.coverPath, modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(NexoDimens.spacingXs)))
            Spacer(modifier = Modifier.height(NexoDimens.spacingXs))
            Text(text = book.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
