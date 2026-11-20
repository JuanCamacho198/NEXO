package com.nexo.presentation.feature.highlights

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.ui.components.molecules.FilterTab
import com.nexo.ui.components.molecules.NexoPillTabs
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.nexoColors

/** Horizontal inset shared by every Highlights section (matches the list cards). */
internal val HighlightsHorizontalInset = 20.dp

private val SwatchSize = 36.dp
private val FilterPillHeight = 40.dp
private const val ColorAnimMs = 200

private val HighlightTypeTabs =
    listOf(
        FilterTab("all", R.string.highlights_tab_all, NexoIcons.Sparkle),
        FilterTab("quotes", R.string.highlights_tab_quotes, NexoIcons.Quote),
        FilterTab("ideas", R.string.highlights_tab_ideas, NexoIcons.Lightbulb),
        FilterTab("passages", R.string.highlights_tab_passages, NexoIcons.AutoStories),
    )

@Composable
fun HighlightsFilterSection(
    uiState: com.nexo.presentation.viewmodel.HighlightsUiState,
    onTypeFilterChanged: (String) -> Unit,
    onBookFilterClick: () -> Unit,
    onTagFilterClick: () -> Unit,
    onColorFilterChanged: (String) -> Unit,
    onColorFilterReset: () -> Unit,
) {
    val filterBookTitle =
        remember(uiState.books, uiState.bookFilter) {
            uiState.bookFilter?.let { bookId ->
                uiState.books.find { it.id == bookId }?.title
            }
        }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NexoPillTabs(
            tabs = HighlightTypeTabs,
            selectedTabId = uiState.typeFilter,
            onTabSelected = onTypeFilterChanged,
            contentPadding = HighlightsHorizontalInset,
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

        // "Recent" only heads a list that exists; with no highlights the empty
        // state speaks for itself. Its old "View all" link had no destination.
        if (uiState.filteredHighlights.isNotEmpty()) {
            Text(
                text = stringResource(R.string.highlights_recent),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = nexoColors.textPrimary,
                modifier = Modifier.padding(start = HighlightsHorizontalInset, end = HighlightsHorizontalInset, top = 8.dp),
            )
        }
    }
}

@Composable
private fun FilterControlsRow(
    bookFilterTitle: String?,
    tagFilter: String?,
    onBookFilterClick: () -> Unit,
    onTagFilterClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = HighlightsHorizontalInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill(
            label = bookFilterTitle ?: stringResource(R.string.highlights_filter_book),
            icon = NexoIcons.Book,
            active = bookFilterTitle != null,
            onClick = onBookFilterClick,
            modifier = Modifier.weight(1f),
        )
        FilterPill(
            label = tagFilter ?: stringResource(R.string.highlights_filter_tag),
            icon = NexoIcons.Tag,
            active = tagFilter != null,
            onClick = onTagFilterClick,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Dropdown-style filter: glyph, current value and a chevron. Tinted `primary`
 * once a value is chosen, so an active filter is visible at a glance.
 */
@Composable
private fun FilterPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = nexoColors
    val container by animateColorAsState(
        targetValue = if (active) colors.primary.copy(alpha = 0.16f) else colors.surface,
        animationSpec = tween(ColorAnimMs),
        label = "filterPillContainer",
    )
    val stroke by animateColorAsState(
        targetValue = if (active) colors.primary else colors.borderSubtle,
        animationSpec = tween(ColorAnimMs),
        label = "filterPillStroke",
    )
    val content = if (active) colors.primary else colors.textPrimary
    Row(
        modifier =
            modifier
                .height(FilterPillHeight)
                .clip(CircleShape)
                .background(container)
                .border(1.dp, stroke, CircleShape)
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = if (active) colors.primary else colors.textSecondary, modifier = Modifier.size(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(imageVector = NexoIcons.ChevronDown, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
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
    val colors = nexoColors
    val allSelected = selectedColors.isEmpty()
    val allLabel = stringResource(R.string.highlights_tab_all)
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = HighlightsHorizontalInset),
    ) {
        item {
            // "All colours": a dashed ring when idle, a solid primary ring when no
            // colour filter is active, so the reset state is visible, not implied.
            val ringColor by animateColorAsState(
                targetValue = if (allSelected) colors.primary else colors.textSecondary,
                animationSpec = tween(ColorAnimMs),
                label = "allSwatchRing",
            )
            Box(
                modifier =
                    Modifier
                        .size(SwatchSize)
                        .clip(CircleShape)
                        .selectable(selected = allSelected, role = Role.RadioButton, onClick = onTodosSelected)
                        .semantics { contentDescription = allLabel },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = 2.dp.toPx()
                    val stroke =
                        if (allSelected) {
                            Stroke(width = width)
                        } else {
                            Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f))
                        }
                    drawCircle(color = ringColor, radius = size.minDimension / 2f - width / 2f, style = stroke)
                }
            }
        }
        items(highlightColors, key = { it.hex }) { highlightColor ->
            ColorSwatch(
                color = parseHighlightColorForFilter(highlightColor.hex),
                selected = highlightColor.hex in selectedColors,
                onToggle = { onColorToggled(highlightColor.hex) },
            )
        }
    }
}

/** One colour filter: pops slightly and shows a check when selected. */
@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "swatchScale",
    )
    Box(
        modifier =
            Modifier
                .size(SwatchSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(CircleShape)
                .background(color)
                .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = NexoIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.Black.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun parseHighlightColorForFilter(hex: String): Color = resolveHighlightColorHex(hex) ?: MaterialTheme.colorScheme.primary
