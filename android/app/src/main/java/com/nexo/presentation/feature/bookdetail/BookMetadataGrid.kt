package com.nexo.presentation.feature.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.util.formatSizeMb
import com.nexo.ui.util.getPagesDisplayText
import com.nexo.ui.util.languageDisplayName
import com.nexo.ui.util.publishedYear

@Composable
internal fun BookMetadataGrid(book: Book) {
    val na = stringResource(R.string.book_detail_na)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NexoDimens.cardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetadataCell(
                    icon = NexoIcons.Book,
                    label = stringResource(R.string.book_detail_meta_format),
                    value = book.format.uppercase(),
                    modifier = Modifier.weight(1f),
                )
                MetadataCell(
                    icon = NexoIcons.Language,
                    label = stringResource(R.string.book_detail_meta_language),
                    value = remember(book.language, na) { languageDisplayName(book.language, na) },
                    modifier = Modifier.weight(1f),
                )
                MetadataCell(
                    icon = NexoIcons.Info,
                    label = stringResource(R.string.book_detail_meta_publisher),
                    value = book.publisher ?: na,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetadataCell(
                    icon = NexoIcons.Clock,
                    label = stringResource(R.string.book_detail_meta_published),
                    value = remember(book.publishedDate, na) { publishedYear(book.publishedDate, na) },
                    modifier = Modifier.weight(1f),
                )
                MetadataCell(
                    icon = NexoIcons.ListBullets,
                    label = stringResource(R.string.book_detail_meta_pages),
                    value = remember(book) { getPagesDisplayText(book, na) },
                    modifier = Modifier.weight(1f),
                )
                MetadataCell(
                    icon = NexoIcons.Storage,
                    label = stringResource(R.string.book_detail_meta_size),
                    value = remember(book.filePath, na) { formatSizeMb(book.filePath, na) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MetadataCell(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
