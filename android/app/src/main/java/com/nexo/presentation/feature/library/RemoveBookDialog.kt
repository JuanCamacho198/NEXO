package com.nexo.presentation.screen.library

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDialogVariant
import com.nexo.ui.theme.NexoTheme

/**
 * 2-step remove dialog mirroring desktop RemoveBookModal.
 *
 * Step 1 (confirm): "¿Quitar de la estantería? Se quitará X de tu estantería."
 *                 [Cancel] [Continue]
 * Step 2 (choose):  two tappable cards:
 *                 - Local only (keeps Drive file)
 *                 - Local + Drive (trash + tombstone)
 *                 [Cancel]
 *
 * State resets to "confirm" every time [bookToDelete] changes (same $effect as desktop).
 */
@Composable
fun RemoveBookDialog(
    bookToDelete: Book?,
    onDismiss: () -> Unit,
    onConfirmLocalOnly: () -> Unit,
    onConfirmLocalAndDrive: () -> Unit,
    @StringRes titleRes: Int = R.string.library_remove_title,
    @StringRes bodyRes: Int = R.string.library_remove_message,
    @StringRes confirmRes: Int = R.string.library_remove_continue,
    @StringRes dismissRes: Int = R.string.library_remove_cancel,
    @StringRes localOnlyTitleRes: Int = R.string.library_remove_local_only,
    @StringRes localOnlySubtitleRes: Int = R.string.library_remove_local_only_subtitle,
    @StringRes localAndDriveTitleRes: Int = R.string.library_remove_local_and_drive,
    @StringRes localAndDriveSubtitleRes: Int = R.string.library_remove_local_and_drive_subtitle,
    @StringRes cancelRes: Int = R.string.library_remove_cancel,
) {
    if (bookToDelete == null) return

    var step by remember(bookToDelete.id) { mutableStateOf("confirm") }

    LaunchedEffect(bookToDelete.id) {
        step = "confirm"
    }

    if (step == "confirm") {
        NexoDialog(
            title = stringResource(titleRes),
            body = stringResource(bodyRes, bookToDelete.title),
            confirmText = stringResource(confirmRes),
            dismissText = stringResource(dismissRes),
            onConfirm = { step = "choose" },
            onDismiss = onDismiss,
            variant = NexoDialogVariant.DESTRUCTIVE,
        )
    } else {
        AlertDialog(
            shape = MaterialTheme.shapes.extraLarge,
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(titleRes)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onConfirmLocalOnly()
                                },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(localOnlyTitleRes),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(localOnlySubtitleRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onConfirmLocalAndDrive() },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(localAndDriveTitleRes),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = stringResource(localAndDriveSubtitleRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(cancelRes))
                }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoveBookDialogConfirmPreview() {
    NexoTheme(darkTheme = false) {
        RemoveBookDialog(
            bookToDelete =
                Book(
                    id = "1",
                    title = "La Odisea",
                    author = "Homero",
                    coverPath = null,
                    filePath = "/books/odisea.epub",
                    format = "epub",
                    updatedAtEpochMillis = 0L,
                ),
            onDismiss = {},
            onConfirmLocalOnly = {},
            onConfirmLocalAndDrive = {},
        )
    }
}
