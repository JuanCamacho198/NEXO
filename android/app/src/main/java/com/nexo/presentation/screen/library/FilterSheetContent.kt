package com.nexo.presentation.screen.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.FilterBottomSheet

@Composable
fun FilterSheetContent(
    showFilterSheet: Boolean,
    filterFormat: String,
    onFormatSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (showFilterSheet) {
        FilterBottomSheet(
            selectedFormat = filterFormat,
            onFormatSelected = onFormatSelected,
            onDismiss = onDismiss,
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun FilterSheetContentDarkPreview() {
    NexoTheme(darkTheme = true) {
        FilterSheetContent(
            showFilterSheet = true,
            filterFormat = "epub",
            onFormatSelected = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FilterSheetContentLightPreview() {
    NexoTheme(darkTheme = false) {
        FilterSheetContent(
            showFilterSheet = true,
            filterFormat = "epub",
            onFormatSelected = {},
            onDismiss = {},
        )
    }
}
