package com.nexo.presentation.feature.highlights

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Highlight
import com.nexo.presentation.feature.highlights.components.NexoHighlightCard
import com.nexo.ui.components.atoms.NexoEmptyState
import com.nexo.ui.icons.NexoIcons

@Composable
fun HighlightsListSection(
    uiState: com.nexo.presentation.viewmodel.HighlightsUiState,
    onCopyHighlight: (Highlight) -> Unit,
    onEditHighlightNote: (Highlight) -> Unit,
    onChangeHighlightColor: (Highlight) -> Unit,
    onViewInBook: (Highlight) -> Unit,
    onAddHighlightTag: (Highlight) -> Unit,
    onDeleteHighlight: (Highlight) -> Unit,
    onTagFilterChanged: (String?) -> Unit,
) {
    val bookMap =
        remember(uiState.books) {
            uiState.books.associate { it.id to it.title }
        }

    if (uiState.filteredHighlights.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            NexoEmptyState(
                icon = NexoIcons.Quote,
                title = stringResource(R.string.highlights_empty),
                subtitle = stringResource(R.string.highlights_empty_subtitle),
            )
        }
    } else {
        for (highlight in uiState.filteredHighlights) {
            NexoHighlightCard(
                content =
                    stripSurroundingQuotes(
                        highlight.textContent.replace("\\n", " ").replace("\n", " "),
                    ),
                accentColor = parseHighlightListColor(highlight.color),
                note = highlight.note,
                tag = highlight.tag,
                attribution = bookMap[highlight.bookId],
                onCopyText = { onCopyHighlight(highlight) },
                onEditNote = { onEditHighlightNote(highlight) },
                onChangeColor = { onChangeHighlightColor(highlight) },
                onViewInBook = { onViewInBook(highlight) },
                onAddTag = { onAddHighlightTag(highlight) },
                onDelete = { onDeleteHighlight(highlight) },
                onTagClick = { tag -> onTagFilterChanged(tag) },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun parseHighlightListColor(hex: String): Color = resolveHighlightColorHex(hex) ?: MaterialTheme.colorScheme.primary

internal fun stripSurroundingQuotes(text: String): String = text.removeSurrounding("\"").removeSurrounding("'")

internal fun resolveHighlightColorHex(hex: String): Color? {
    val s = hex.trim().removePrefix("#")
    return when (s.length) {
        6 -> runCatching { Color(("FF" + s).toLong(16)) }.getOrNull()
        8 -> runCatching { Color(s.toLong(16)) }.getOrNull()
        else -> null
    }.takeIf { it?.alpha != 0f }
}
