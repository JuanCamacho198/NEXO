package com.nexo.presentation.feature.bookdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.domain.model.Book
import com.nexo.feature.bookdetail.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoDimens
import com.nexo.ui.theme.nexoColors
import com.nexo.ui.util.formatSizeMb
import com.nexo.ui.util.getPagesDisplayText
import com.nexo.ui.util.languageDisplayName
import com.nexo.ui.util.publishedYear

@Composable
internal fun BookMetadataGrid(book: Book) {
    val na = stringResource(R.string.book_detail_na)
    val colors = nexoColors
    val shape = RoundedCornerShape(NexoDimens.cardCornerRadius + 4.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, colors.borderSubtle, shape),
        shape = shape,
        color = colors.surface,
        contentColor = colors.textSecondary,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 18.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                MetadataCell(
                    icon = NexoIcons.Book,
                    label = stringResource(R.string.book_detail_meta_format),
                    value = book.format.uppercase(),
                    modifier = Modifier.weight(1f),
                )
                CellDivider()
                MetadataCell(
                    icon = NexoIcons.Language,
                    label = stringResource(R.string.book_detail_meta_language),
                    value = remember(book.language, na) { languageDisplayName(book.language, na) },
                    modifier = Modifier.weight(1f),
                )
                CellDivider()
                MetadataCell(
                    icon = NexoIcons.Info,
                    label = stringResource(R.string.book_detail_meta_publisher),
                    value = book.publisher ?: na,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                MetadataCell(
                    icon = NexoIcons.Clock,
                    label = stringResource(R.string.book_detail_meta_published),
                    value = remember(book.publishedDate, na) { publishedYear(book.publishedDate, na) },
                    modifier = Modifier.weight(1f),
                )
                CellDivider()
                MetadataCell(
                    icon = NexoIcons.ListBullets,
                    label = stringResource(R.string.book_detail_meta_pages),
                    value = remember(book) { getPagesDisplayText(book, na) },
                    modifier = Modifier.weight(1f),
                )
                CellDivider()
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
private fun CellDivider() {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .width(1.dp)
                .background(nexoColors.borderSubtle),
    )
}

/**
 * One fact: the value leads (it is what the reader scans for), the label sits
 * under it. The value may wrap to two lines, so "English (United States)" is
 * shown whole instead of being cut to "English (United ...".
 */
@Composable
private fun MetadataCell(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    Column(
        modifier = modifier.padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
