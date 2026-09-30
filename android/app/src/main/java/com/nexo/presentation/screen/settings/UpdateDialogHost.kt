package com.nexo.presentation.screen.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.presentation.viewmodel.UpdateUiState
import com.nexo.presentation.viewmodel.UpdateViewModel

@Composable
fun UpdateDialogHost(viewModel: UpdateViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val meteredConsent by viewModel.meteredConsentPending.collectAsStateWithLifecycle()
    val installGuidance by viewModel.installGuidanceVisible.collectAsStateWithLifecycle()

    val available = state as? UpdateUiState.Available
    if (available != null) {
        val notes = available.candidate.notes
        val body =
            if (notes.isBlank()) {
                stringResource(R.string.update_available_body, available.candidate.version)
            } else {
                stringResource(R.string.update_available_body, available.candidate.version) + "\n\n" + notes
            }
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.update_available_title)) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = viewModel::updateNow) {
                    Text(stringResource(R.string.update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::remindLater) {
                    Text(stringResource(R.string.update_later))
                }
            },
        )
    }

    if (meteredConsent) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMeteredConsent,
            title = { Text(stringResource(R.string.update_available_title)) },
            text = { Text(stringResource(R.string.update_metered_consent)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMeteredDownload) {
                    Text(stringResource(R.string.update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMeteredConsent) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (installGuidance) {
        AlertDialog(
            onDismissRequest = viewModel::dismissInstallGuidance,
            title = { Text(stringResource(R.string.update_available_title)) },
            text = { Text(stringResource(R.string.update_install_guidance)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissInstallGuidance) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}
