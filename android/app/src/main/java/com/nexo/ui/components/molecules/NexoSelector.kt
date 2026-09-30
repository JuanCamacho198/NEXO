package com.nexo.ui.components.molecules

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoBottomSheet
import com.nexo.ui.icons.NexoIcons

data class SelectorOption(
    val id: String,
    @param:StringRes val labelRes: Int? = null,
    val label: String? = null,
    val icon: ImageVector? = null,
)

/**
 * Modal bottom sheet that lets the user pick one option from a list
 * (radio-style). Wraps [NexoBottomSheet] with a `LazyColumn` of
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
    NexoBottomSheet(
        title = title,
        onDismiss = onDismiss,
    ) {
        LazyColumn {
            items(options, key = { it.id }) { option ->
                val isSelected = option.id == selectedOptionId
                val optionLabel =
                    option.label
                        ?: option.labelRes?.let { stringResource(it) }
                        ?: option.id
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOptionSelected(option)
                                onDismiss()
                            }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    option.icon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = optionLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = NexoIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
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
