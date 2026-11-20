package com.nexo.presentation.feature.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoEmptyState
import com.nexo.ui.motion.pressScaleClickable
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.nexoColors

@Composable
fun MyBookshelfSection(
    books: List<Book>,
    onViewAll: () -> Unit,
    onBookSelected: (String, String, String) -> Unit,
) {
    Column {
        HomeSectionHeader(title = stringResource(R.string.home_my_bookshelf_title), onAction = onViewAll)
        if (books.isNotEmpty()) {
            Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(BookshelfCardGap)) {
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
    val colors = nexoColors
    val coverShape = RoundedCornerShape(NexoDimens.coverCornerRadius)
    Column(
        modifier = Modifier.width(BookshelfCardWidth).pressScaleClickable(shape = coverShape, onClick = onClick),
    ) {
        CoverThumbnail(
            coverPath = book.coverPath,
            contentDescription = stringResource(R.string.library_cover_content_description),
            modifier =
                Modifier
                    .width(BookshelfCardWidth)
                    .height(BookshelfCoverHeight)
                    .clip(coverShape)
                    .border(1.dp, colors.borderSubtle, coverShape),
        )
        Spacer(modifier = Modifier.height(NexoDimens.spacingSm))
        Text(
            text = book.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        // Always reserve the author line, so a book without one keeps the row aligned.
        Text(
            text = book.author.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Fixed card geometry for the shelf row.
 *
 * The cover is portrait (2:3, the shape of a book), and both the two-line title
 * and the author line are always reserved, so every card in the row has the
 * same height whatever metadata its book carries.
 */
private val BookshelfCardWidth = 108.dp
private val BookshelfCoverHeight = 162.dp
private val BookshelfCardGap = 14.dp
