package com.nexo.ui.components.atoms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme

/**
 * Modal bottom sheet with a centered `titleLarge` header and a
 * `ColumnScope` content slot. Wraps Material 3 `ModalBottomSheet` with
 * the NEXO padding defaults.
 *
 * @param title Header text rendered in `MaterialTheme.typography.titleLarge`,
 *   center-aligned, with 16dp bottom padding.
 * @param onDismiss Invoked when the user swipes the sheet down, taps
 *   the scrim, or presses back. Clear the show-state in the ViewModel
 *   here.
 * @param modifier Modifier applied to the underlying `ModalBottomSheet`.
 * @param sheetState The Material 3 sheet state. Default uses
 *   `rememberModalBottomSheetState(skipPartiallyExpanded = true)` so the
 *   sheet opens fully expanded (no half-expanded snap point). Pass a
 *   hoisted state if you need programmatic control.
 * @param content Composable content rendered below the title. Receives
 *   a `ColumnScope` and is wrapped in a `Column` with `fillMaxWidth()`
 *   and 24dp horizontal + 24dp bottom padding.
 *
 * **Visual**: standard Material 3 bottom sheet, drag handle included
 * by default. Title is centered horizontally. Content has uniform
 * 24dp horizontal padding and 24dp bottom padding.
 * **Behavior**: modal — blocks the underlying screen and traps focus.
 * Dismisses on scrim tap, swipe-down, or back press. There is no
 * explicit close button — the user dismisses via gesture or you
 * dismiss programmatically through [sheetState].
 * **Recomposition**: recomposes when `title` or `onDismiss` change.
 * [sheetState] is stable; updating it does not retrigger composition
 * of this composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NexoBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .align(Alignment.CenterHorizontally),
            )
            content()
        }
    }
}

@Preview(showBackground = true)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexoBottomSheetDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoBottomSheet(
            title = "Sort library",
            onDismiss = {},
        ) {
            Text(
                text = "Recently added\nTitle A-Z\nAuthor",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(showBackground = true)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NexoBottomSheetLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoBottomSheet(
            title = "Sort library",
            onDismiss = {},
        ) {
            Text(
                text = "Recently added\nTitle A-Z\nAuthor",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
