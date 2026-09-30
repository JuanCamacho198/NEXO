package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme

/**
 * Anchored 280dp-wide card for attaching a tag to a highlight. Shows
 * a single-line text field and (when [suggestions] is non-empty) a
 * wrapping `FlowRow` of suggestion chips. Auto-requests focus and
 * shows the soft keyboard on first composition.
 *
 * @param tag Current tag text. Hoisted state owned by the parent.
 * @param suggestions Suggestion chips rendered below the text field.
 *   Hidden when the list is empty.
 * @param onTagChange Invoked on every keystroke.
 * @param onSuggestionClick Invoked with the tapped suggestion's
 *   text. The parent typically pre-fills the text field and may
 *   auto-save.
 * @param onSave Invoked when the user taps "Save" OR presses the
 *   IME `Done` action.
 * @param onDismiss Invoked when the user taps "Cancel".
 * @param modifier Modifier applied to the outer `Surface`.
 *
 * **Visual**: 280dp-wide `Surface`, 16dp corners, 8dp elevation.
 *   Single-line `OutlinedTextField` (label "Tag", `ImeAction.Done`).
 *   Suggestions shown as pill chips (50% corner radius) with a
 *   `outline` border, `surfaceContainerHighest` background, and
 *   `primary` text. Footer: text-button "Cancel" + filled-button
 *   "Save", right-aligned with 8dp gap.
 * **Behavior**: `LaunchedEffect(Unit)` focuses the tag field and
 *   opens the keyboard. Tapping a suggestion fires
 *   [onSuggestionClick] only — it does NOT auto-save; the parent
 *   decides whether to call [onSave] in response. Tapping "Save" or
 *   the IME "Done" key both fire [onSave].
 * **Recomposition**: recomposes when any parameter changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnchoredTagInput(
    tag: String,
    suggestions: List<String>,
    onTagChange: (String) -> Unit,
    onSuggestionClick: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Surface(
        modifier = modifier.width(280.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            OutlinedTextField(
                value = tag,
                onValueChange = onTagChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                label = { Text(stringResource(R.string.tag_input_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )

            if (suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(
                            label = suggestion,
                            onClick = { onSuggestionClick(suggestion) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.reader_cancel))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onSave) {
                    Text(stringResource(R.string.reader_save))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AnchoredTagInputDarkPreview() {
    NexoTheme(darkTheme = true) {
        AnchoredTagInput(
            tag = "philosophy",
            suggestions = listOf("philosophy", "classics", "to-review"),
            onTagChange = {},
            onSuggestionClick = {},
            onSave = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AnchoredTagInputLightPreview() {
    NexoTheme(darkTheme = false) {
        AnchoredTagInput(
            tag = "philosophy",
            suggestions = listOf("philosophy", "classics", "to-review"),
            onTagChange = {},
            onSuggestionClick = {},
            onSave = {},
            onDismiss = {},
        )
    }
}

@Composable
private fun SuggestionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(50),
                ).background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(50),
                ).clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
