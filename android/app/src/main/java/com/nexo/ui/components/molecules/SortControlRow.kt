package com.nexo.ui.components.molecules

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

/**
 * Row shown above the library list: a sort-by label and selector
 * button on the left, and a grid/list view toggle on the right.
 *
 * Owns the [showSortSelector] state internally; when the user taps
 * the sort label, an inline [NexoSelector] bottom sheet opens
 * with the four hard-coded options (date added, title, author, last
 * read).
 *
 * @param sortBy Currently selected sort id (one of `"date_added"`,
 *   `"title"`, `"author"`, `"last_read"`). If the id is unknown the
 *   selector falls back to "date added" for the display label only;
 *   the underlying [sortBy] value is preserved.
 * @param onSortByChanged Invoked with the new sort id after the user
 *   confirms a selection. The bottom sheet auto-dismisses.
 * @param isGridView `true` when the library is in grid layout,
 *   `false` for list layout. Drives the active tint on the toggle
 *   icons.
 * @param onViewToggle Invoked when the user taps the inactive
 *   toggle. Tapping the active toggle is a no-op (the `if` guard
 *   prevents redundant calls).
 *
 * **Visual**: row with 16dp horizontal padding. Left side: small
 * `bodyMedium` "Sort by" label followed by a `TEXT`-variant
 * [NexoButton] showing the active option's localized label.
 * Right side: two `IconButton`s for grid/list; the active one is
 * tinted `colorScheme.primary`, the inactive one is
 * `colorScheme.onSurfaceVariant`.
 * **Behavior**: tap the sort label → opens a modal selector sheet
 * that auto-dismisses on selection. Tap the inactive view icon →
 * calls [onViewToggle]. Tapping the active icon is a no-op.
 * **Recomposition**: recomposes when `sortBy`, `isGridView`, or any
 * callback changes.
 */
@Composable
fun SortControlRow(
    sortBy: String,
    onSortByChanged: (String) -> Unit,
    isGridView: Boolean,
    onViewToggle: () -> Unit,
    trailingStatus: (@Composable () -> Unit)? = null,
) {
    var showSortSelector by remember { mutableStateOf(false) }

    val sortOptions =
        listOf(
            SelectorOption("date_added", R.string.library_sort_date_added),
            SelectorOption("title", R.string.library_sort_title),
            SelectorOption("author", R.string.library_sort_author),
            SelectorOption("last_read", R.string.library_sort_last_read),
        )

    val selectedSortLabel =
        sortOptions.find { it.id == sortBy }?.labelRes
            ?: R.string.library_sort_date_added

    if (showSortSelector) {
        NexoSelector(
            title = stringResource(R.string.library_sort_label),
            options = sortOptions,
            selectedOptionId = sortBy,
            onOptionSelected = { option ->
                onSortByChanged(option.id)
                showSortSelector = false
            },
            onDismiss = { showSortSelector = false },
        )
    }

    val colors = nexoColors
    val sortLabel = stringResource(R.string.library_sort_label)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sort pill: the label is the accessible name, the active option the value.
        Row(
            modifier =
                Modifier
                    .height(ControlHeight)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, colors.borderSubtle, CircleShape)
                    .clickable(onClickLabel = sortLabel) { showSortSelector = true }
                    .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(selectedSortLabel),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Icon(
                imageVector = NexoIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (trailingStatus != null) trailingStatus()

        // Segmented grid/list toggle; the active segment's fill animates across.
        Row(
            modifier =
                Modifier
                    .height(ControlHeight)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, colors.borderSubtle, CircleShape)
                    .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewSegment(
                icon = NexoIcons.GridView,
                contentDescription = stringResource(R.string.library_view_grid),
                selected = isGridView,
                onClick = { if (!isGridView) onViewToggle() },
            )
            ViewSegment(
                icon = NexoIcons.ViewList,
                contentDescription = stringResource(R.string.library_view_list),
                selected = !isGridView,
                onClick = { if (isGridView) onViewToggle() },
            )
        }
    }
}

private val ControlHeight = 38.dp
private val SegmentWidth = 40.dp
private const val SegmentColorMs = 220

@Composable
private fun ViewSegment(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = nexoColors
    val container by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.surface,
        animationSpec = tween(SegmentColorMs),
        label = "segmentContainer",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else colors.textSecondary,
        animationSpec = tween(SegmentColorMs),
        label = "segmentTint",
    )
    Box(
        modifier =
            Modifier
                .width(SegmentWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(container)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun SortControlRowDarkPreview() {
    NexoTheme(darkTheme = true) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortControlRow(
                sortBy = "title",
                onSortByChanged = {},
                isGridView = false,
                onViewToggle = {},
            )
            SortControlRow(
                sortBy = "title",
                onSortByChanged = {},
                isGridView = true,
                onViewToggle = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SortControlRowLightPreview() {
    NexoTheme(darkTheme = false) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortControlRow(
                sortBy = "title",
                onSortByChanged = {},
                isGridView = false,
                onViewToggle = {},
            )
            SortControlRow(
                sortBy = "title",
                onSortByChanged = {},
                isGridView = true,
                onViewToggle = {},
            )
        }
    }
}
