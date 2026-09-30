package com.nexo.presentation.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import com.nexo.ui.components.atoms.CoverThumbnail
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons

@Composable
fun SearchBarSection(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        NexoTextField(value = searchQuery, onValueChange = onSearchQueryChange, placeholder = stringResource(R.string.library_search_placeholder), singleLine = true, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        NexoButton(onClick = onCloseSearch, variant = NexoButtonVariant.ICON) { Icon(imageVector = NexoIcons.Close, contentDescription = stringResource(R.string.home_close_search)) }
    }
}

@Composable
fun SearchResultsList(
    results: List<Book>,
    onBookSelected: (String, String, String) -> Unit,
) {
    Column {
        Text(text = stringResource(R.string.home_search_results), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        results.forEach { book ->
            Surface(modifier = Modifier.fillMaxWidth().clickable { onBookSelected(book.id, book.filePath, book.format) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CoverThumbnail(coverPath = book.coverPath, modifier = Modifier.width(40.dp).height(56.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        book.author?.let { author -> Text(text = author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
