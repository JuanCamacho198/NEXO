package com.nexo.presentation.feature.highlights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.HighlightColor
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.molecules.HighlightAnnotationModal

@Composable
fun HighlightsDialogs(
    uiState: com.nexo.presentation.viewmodel.HighlightsUiState,
    onSaveHighlightNote: (String) -> Unit,
    onDismissEditHighlight: () -> Unit,
    onDismissDeleteHighlightDialog: () -> Unit,
    onConfirmDeleteHighlight: () -> Unit,
    onDismissColorPicker: () -> Unit,
    onConfirmColorChange: (String) -> Unit,
    onDismissTagEdit: () -> Unit,
    onTagEditTextChanged: (String) -> Unit,
    onSaveHighlightTag: (String) -> Unit,
) {
    uiState.highlightToEdit?.let { highlight ->
        HighlightAnnotationModal(
            titleRes = R.string.note_modal_title,
            hintRes = R.string.annotation_textarea_note_hint,
            snippetLabelRes = R.string.annotation_snippet_label,
            selectedText = highlight.textContent.replace("\\n", " ").replace("\n", " "),
            initialText = uiState.editNoteText,
            onSave = onSaveHighlightNote,
            onDismiss = onDismissEditHighlight,
        )
    }

    uiState.highlightToDelete?.let { highlight ->
        AlertDialog(
            onDismissRequest = onDismissDeleteHighlightDialog,
            title = { Text(text = stringResource(R.string.highlights_delete_title)) },
            text = {
                Text(
                    text =
                        stringResource(
                            R.string.highlights_delete_message,
                            highlight.textContent
                                .replace("\\n", " ")
                                .replace("\n", " ")
                                .take(60),
                        ),
                )
            },
            confirmButton = {
                NexoButton(
                    onClick = onConfirmDeleteHighlight,
                    variant = NexoButtonVariant.TEXT,
                ) {
                    Text(text = stringResource(R.string.highlights_delete_confirm))
                }
            },
            dismissButton = {
                NexoButton(
                    onClick = onDismissDeleteHighlightDialog,
                    variant = NexoButtonVariant.TEXT,
                ) {
                    Text(text = stringResource(R.string.reader_cancel))
                }
            },
        )
    }

    uiState.selectedHighlightForColorChange?.let { highlight ->
        AlertDialog(
            onDismissRequest = onDismissColorPicker,
            title = { Text(stringResource(R.string.highlights_menu_change_color)) },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    for (color in HighlightColor.entries) {
                        val isActive = color.hex.equals(highlight.color, ignoreCase = true)
                        Box(
                            modifier =
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(parseDialogColor(color.hex))
                                    .clickable { onConfirmColorChange(color.hex) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isActive) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(36.dp)
                                            .border(2.dp, Color.White, CircleShape),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissColorPicker) {
                    Text(stringResource(R.string.reader_cancel))
                }
            },
        )
    }

    uiState.selectedHighlightForTagEdit?.let { _ ->
        AlertDialog(
            onDismissRequest = onDismissTagEdit,
            title = {
                Text(
                    stringResource(
                        if (uiState.editTagText.isBlank()) {
                            R.string.highlights_menu_add_tag
                        } else {
                            R.string.highlights_menu_edit_tag
                        },
                    ),
                )
            },
            text = {
                OutlinedTextField(
                    value = uiState.editTagText,
                    onValueChange = onTagEditTextChanged,
                    placeholder = { Text(stringResource(R.string.highlights_tag_dialog_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { onSaveHighlightTag(uiState.editTagText) }) {
                    Text(stringResource(R.string.reader_save))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissTagEdit) {
                    Text(stringResource(R.string.reader_cancel))
                }
            },
        )
    }
}

@Composable
private fun parseDialogColor(hex: String): Color = resolveHighlightColorHex(hex) ?: MaterialTheme.colorScheme.primary
