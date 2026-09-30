package com.nexo.presentation.feature.highlights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.ui.components.molecules.FilterTab
import com.nexo.ui.components.molecules.NexoFilterTabs
import com.nexo.ui.components.molecules.NexoSectionHeader
import com.nexo.ui.icons.NexoIcons

@Composable
fun HighlightsFilterSection(
    uiState: com.nexo.presentation.viewmodel.HighlightsUiState,
    onTypeFilterChanged: (String) -> Unit,
    onBookFilterClick: () -> Unit,
    onTagFilterClick: () -> Unit,
    onColorFilterChanged: (String) -> Unit,
    onColorFilterReset: () -> Unit,
) {
    val typeTabs =
        listOf(
            FilterTab("all", R.string.highlights_tab_all, NexoIcons.Sparkle),
            FilterTab("quotes", R.string.highlights_tab_quotes, NexoIcons.Quote),
            FilterTab("ideas", R.string.highlights_tab_ideas, NexoIcons.Lightbulb),
            FilterTab("passages", R.string.highlights_tab_passages, NexoIcons.Sparkle),
        )

    val filterBookTitle =
        remember(uiState.books, uiState.bookFilter) {
            uiState.bookFilter?.let { bookId ->
                uiState.books.find { it.id == bookId }?.title
            }
        }

    NexoFilterTabs(
        tabs = typeTabs,
        selectedTabId = uiState.typeFilter,
        onTabSelected = onTypeFilterChanged,
    )

    FilterControlsRow(
        bookFilterTitle = filterBookTitle,
        tagFilter = uiState.tagFilter,
        onBookFilterClick = onBookFilterClick,
        onTagFilterClick = onTagFilterClick,
    )

    ColorSwatchRow(
        selectedColors = uiState.colorFilter,
        highlightColors = HighlightColor.entries,
        onColorToggled = onColorFilterChanged,
        onTodosSelected = onColorFilterReset,
    )

    NexoSectionHeader(
        title = stringResource(R.string.highlights_recent),
        actionLabel = stringResource(R.string.home_ver_todo),
        onActionClick = { },
    )
}

@Composable
private fun FilterControlsRow(
    bookFilterTitle: String?,
    tagFilter: String?,
    onBookFilterClick: () -> Unit,
    onTagFilterClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = bookFilterTitle != null,
            onClick = onBookFilterClick,
            label = { Text(bookFilterTitle ?: stringResource(R.string.highlights_filter_book)) },
            colors =
                FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = tagFilter != null,
            onClick = onTagFilterClick,
            label = { Text(tagFilter ?: stringResource(R.string.highlights_filter_tag)) },
            colors =
                FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ColorSwatchRow(
    selectedColors: Set<String>,
    highlightColors: List<HighlightColor>,
    onColorToggled: (String) -> Unit,
    onTodosSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        item {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onTodosSelected() },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke =
                        Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f),
                        )
                    drawCircle(
                        color = Color.Gray,
                        radius = size.minDimension / 2f - 2.dp.toPx() / 2f,
                        style = stroke,
                    )
                }
            }
        }
        items(highlightColors, key = { it.hex }) { highlightColor ->
            val isSelected = highlightColor.hex in selectedColors
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(parseHighlightColorForFilter(highlightColor.hex))
                        .clickable { onColorToggled(highlightColor.hex) }
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, Color.White, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = NexoIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.Black,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun parseHighlightColorForFilter(hex: String): Color = resolveHighlightColorHex(hex) ?: MaterialTheme.colorScheme.primary
