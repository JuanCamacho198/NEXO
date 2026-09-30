package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme

/**
 * Modal bottom sheet for filtering the library by file format. Shows
 * a single "Format" filter group with three mutually-exclusive chips
 * (All, EPUB, PDF).
 *
 * @param selectedFormat Currently selected format id: `"all"`, `"epub"`,
 *   or `"pdf"`. Compared case-sensitively.
 * @param onFormatSelected Invoked with the new format id whenever the
 *   user taps a chip. Note: the sheet does NOT auto-dismiss on
 *   selection — the caller should pair this with a state update that
 *   hides the sheet.
 * @param onDismiss Invoked when the user swipes down, taps the scrim,
 *   or presses back.
 *
 * **Visual**: standard Material 3 `ModalBottomSheet` with a
 * `titleLarge` header ("Filter"), a `labelLarge` section label
 * ("Format"), and a row of three `FilterChip`s with 16dp pill corners.
 * Selected chip uses `colorScheme.primary` background and
 * `colorScheme.onPrimary` label.
 * **Behavior**: tap a chip → `onFormatSelected(id)`. The caller is
 * responsible for closing the sheet (this differs from the
 * `NexoSelector` pattern where selection auto-dismisses).
 * **Recomposition**: recomposes when `selectedFormat` or
 * `onFormatSelected` changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBottomSheet(
    selectedFormat: String,
    onFormatSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.library_filter_label),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Text(
                text = stringResource(R.string.library_filter_format_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FormatChip(
                    label = stringResource(R.string.library_filter_format_all),
                    isSelected = selectedFormat == "all",
                    onClick = { onFormatSelected("all") },
                )
                FormatChip(
                    label = "EPUB",
                    isSelected = selectedFormat == "epub",
                    onClick = { onFormatSelected("epub") },
                )
                FormatChip(
                    label = "PDF",
                    isSelected = selectedFormat == "pdf",
                    onClick = { onFormatSelected("pdf") },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FormatChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(16.dp),
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
    )
}

@Preview(showBackground = true)
@Composable
private fun FilterBottomSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        FilterBottomSheet(
            selectedFormat = "epub",
            onFormatSelected = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FilterBottomSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        FilterBottomSheet(
            selectedFormat = "epub",
            onFormatSelected = {},
            onDismiss = {},
        )
    }
}
