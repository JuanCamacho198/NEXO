package com.nexo.presentation.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoColors
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoEmptyState
import com.nexo.ui.icons.NexoIcons

@Composable
fun MyBookshelfSection(
    books: List<Book>,
    onViewAll: () -> Unit,
    onBookSelected: (String, String, String) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_my_bookshelf_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = NexoColors.textPrimary,
            )
            Row(
                modifier = Modifier.clickable(onClick = onViewAll).padding(NexoDimens.spacingXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_ver_todo),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = NexoColors.accentOrange,
                )
                Icon(
                    imageVector = NexoIcons.ChevronRight,
                    contentDescription = null,
                    tint = NexoColors.accentOrange,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (books.isNotEmpty()) {
            Spacer(modifier = Modifier.height(NexoDimens.spacingMd))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd)) {
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
    Column(
        modifier = Modifier.width(BookshelfCardWidth).clickable(onClick = onClick),
    ) {
        CoverThumbnail(
            coverPath = book.coverPath,
            modifier =
                Modifier
                    .width(BookshelfCardWidth)
                    .height(BookshelfCoverHeight)
                    .clip(RoundedCornerShape(NexoDimens.spacingXs)),
        )
        Spacer(modifier = Modifier.height(NexoDimens.spacingXs))
        Text(
            text = book.title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = NexoColors.textPrimary,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(BookshelfTitleHeight),
        )
        book.author?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.labelSmall,
                color = NexoColors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Fixed card geometry for the shelf row.
 *
 * The cover is portrait (2:3, the shape of a book) rather than matching the card
 * width, and the title reserves exactly two lines, so every card in the row has
 * the same height whether its book has a one- or two-line title.
 */
private val BookshelfCardWidth = 92.dp
private val BookshelfCoverHeight = 138.dp
private val BookshelfTitleHeight = 30.dp
