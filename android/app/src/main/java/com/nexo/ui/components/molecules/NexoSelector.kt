package com.nexo.ui.components.molecules

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.ui.components.atoms.NexoBottomSheet
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

data class SelectorOption(
    val id: String,
    @param:StringRes val labelRes: Int? = null,
    val label: String? = null,
    val icon: ImageVector? = null,
)

/**
 * Modal bottom sheet that lets the user pick one option from a list
 * (radio-style); the selected row is tinted with a check that pops in. Wraps [NexoBottomSheet] with a `LazyColumn` of
 * options; the active option shows a checkmark. Tapping any option
 * invokes [onOptionSelected] AND auto-dismisses the sheet.
 *
 * @param title Sheet title shown at the top.
 * @param options The list of options. Order is preserved in the UI.
 * @param selectedOptionId Id of the currently selected option. Used
 *   to render the checkmark. Matched with `option.id` exactly.
 * @param onOptionSelected Invoked with the chosen [SelectorOption]
 *   when the user taps a row. The sheet auto-dismisses immediately
 *   after via [onDismiss].
 * @param onDismiss Invoked on swipe-down, scrim-tap, back-press, or
 *   after a successful selection (the caller should clear the show-
 *   state in the ViewModel).
 *
 * **Visual**: `NexoBottomSheet` header + a vertically scrolling
 *   list of rows. Each row shows (optional icon, label, optional
 *   checkmark). The label is resolved as `option.label ?:
 *   option.labelRes-stringResource ?: option.id`. The checkmark is
 *   shown only on the row matching [selectedOptionId], tinted
 *   `colorScheme.primary`.
 * **Behavior**: tap a row → [onOptionSelected] then [onDismiss].
 *   Standard sheet dismissal also calls [onDismiss].
 * **Recomposition**: recomposes when `title`, `options`,
 *   `selectedOptionId`, or callbacks change.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NexoSelector(
    title: String,
    options: List<SelectorOption>,
    selectedOptionId: String,
    onOptionSelected: (SelectorOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasIconColumn = options.any { it.icon != null }
    NexoBottomSheet(
        title = title,
        onDismiss = onDismiss,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(options, key = { it.id }) { option ->
                val optionLabel =
                    option.label
                        ?: option.labelRes?.let { stringResource(it) }
                        ?: option.id
                SelectorRow(
                    label = optionLabel,
                    icon = option.icon,
                    selected = option.id == selectedOptionId,
                    hasIconColumn = hasIconColumn,
                    onClick = {
                        onOptionSelected(option)
                        onDismiss()
                    },
                )
            }
        }
    }
}

private val RowShape = RoundedCornerShape(14.dp)
private val RowMinHeight = 52.dp
private val IconSlot = 24.dp
private const val SelectAnimMs = 180

/**
 * One option. The leading slot is reserved for EVERY row as soon as any option
 * has an icon, so labels share one left edge; before, only the iconed row was
 * indented and the list read as misaligned. The selected row is tinted, its
 * label bold `primary`, and its check pops in.
 */
@Composable
private fun SelectorRow(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    hasIconColumn: Boolean,
    onClick: () -> Unit,
) {
    val colors = nexoColors
    val container by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(SelectAnimMs),
        label = "selectorRow",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "selectorCheck",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .clip(RowShape)
                .background(container)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (hasIconColumn) {
            Box(modifier = Modifier.size(IconSlot), contentAlignment = Alignment.Center) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) colors.primary else colors.textSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    }.clip(CircleShape)
                    .background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = NexoIcons.Check,
                    contentDescription = stringResource(com.nexo.R.string.a11y_selected),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoSelectorDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoSelector(
            title = "Sort by",
            options =
                listOf(
                    SelectorOption(id = "recent", label = "Recently added"),
                    SelectorOption(id = "title", label = "Title A-Z"),
                    SelectorOption(id = "author", label = "Author"),
                ),
            selectedOptionId = "title",
            onOptionSelected = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoSelectorLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoSelector(
            title = "Sort by",
            options =
                listOf(
                    SelectorOption(id = "recent", label = "Recently added"),
                    SelectorOption(id = "title", label = "Title A-Z"),
                    SelectorOption(id = "author", label = "Author"),
                ),
            selectedOptionId = "title",
            onOptionSelected = {},
            onDismiss = {},
        )
    }
}
