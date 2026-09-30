package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * Anchored 280dp-wide card for adding/editing the user-supplied
 * definition of a selected word (dictionary flow). Auto-requests
 * focus and shows the soft keyboard on first composition.
 *
 * @param word The word being defined. Rendered as a `titleSmall`
 *   label above the text field. Caller is responsible for actually
 *   saving this — this composable does not own dictionary state.
 * @param definition Current definition text. Hoisted state owned by
 *   the parent.
 * @param onDefinitionChange Invoked on every keystroke in the
 *   definition text field.
 * @param onSave Invoked when the user taps the "Save" button OR
 *   presses the IME `Done` action. The caller is expected to close
 *   the surrounding overlay.
 * @param onDismiss Invoked when the user taps "Cancel". The caller
 *   is expected to close the surrounding overlay.
 * @param modifier Modifier applied to the outer `Surface`.
 *
 * **Visual**: 280dp-wide `Surface` with 16dp corner radius, 8dp
 *   elevation. Header: `titleSmall` word. Multi-line `OutlinedTextField`
 *   with 2-4 line range, "Definition" label, surface-colored
 *   container. Footer: text-button "Cancel" + filled-button "Save",
 *   right-aligned with 8dp gap.
 * **Behavior**: `LaunchedEffect(Unit)` requests focus on the
 *   definition field and shows the soft keyboard. Tapping the IME
 *   "Done" action fires the same [onSave] as the Save button.
 *   The composable does NOT close itself on save — the caller
 *   must trigger dismissal.
 * **Recomposition**: recomposes when any parameter changes.
 */
@Composable
fun AnchoredDefinitionInput(
    word: String,
    definition: String,
    onDefinitionChange: (String) -> Unit,
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
            Text(
                text = word,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = definition,
                onValueChange = onDefinitionChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                label = { Text(stringResource(R.string.definition_input_hint)) },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
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
private fun AnchoredDefinitionInputDarkPreview() {
    NexoTheme(darkTheme = true) {
        AnchoredDefinitionInput(
            word = "serendipity",
            definition = "The occurrence of events by chance in a happy or beneficial way.",
            onDefinitionChange = {},
            onSave = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AnchoredDefinitionInputLightPreview() {
    NexoTheme(darkTheme = false) {
        AnchoredDefinitionInput(
            word = "serendipity",
            definition = "The occurrence of events by chance in a happy or beneficial way.",
            onDefinitionChange = {},
            onSave = {},
            onDismiss = {},
        )
    }
}
