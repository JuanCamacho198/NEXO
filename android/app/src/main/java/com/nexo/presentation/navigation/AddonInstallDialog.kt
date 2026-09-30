package com.nexo.presentation.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.ui.components.atoms.NexoDialog

/**
 * Plain-string copy (desktop addon-deeplink-v1 precedent: plain-string dialog
 * constants instead of growing the i18n MessageKey registry).
 */
internal object AddonInstallDialogText {
    const val TITLE = "Install addon?"
    const val PUBLISHER_LABEL = "Publisher"
    const val INSTALL = "Install"
    const val CANCEL = "Cancel"
    const val INSTALLING_TITLE = "Installing addon…"
    const val ERROR_TITLE = "Couldn't install addon"
    const val OK = "OK"

    fun errorBody(code: AddonFetchErrorCode): String =
        when (code) {
            AddonFetchErrorCode.HTTPS_REQUIRED -> "Install links must use https."
            AddonFetchErrorCode.NETWORK -> "Could not download the addon. Check your connection and try again."
            AddonFetchErrorCode.TOO_LARGE -> "The addon manifest is too large."
            AddonFetchErrorCode.BAD_CONTENT_TYPE -> "The addon manifest is not valid JSON."
            AddonFetchErrorCode.INVALID_MANIFEST -> "This addon manifest is invalid."
        }
}

/**
 * Confirmation dialog for `nextpage://install` deep links, hosted at the
 * NexoNavHost root (DrivePromptHost pattern). Renders [InstallUiState]:
 * Confirming → NexoDialog (name, version, catalog count, source URL);
 * Fetching (post-confirm) → busy dialog; Error → error dialog. Cancel and
 * outside-tap dismiss abort with nothing installed (no silent installs).
 */
@Composable
fun AddonInstallDialogHost(controller: InstallDeepLinkController) {
    val state by controller.state.collectAsStateWithLifecycle()
    when (val current = state) {
        InstallUiState.Idle -> Unit
        InstallUiState.Fetching -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(AddonInstallDialogText.INSTALLING_TITLE) },
                text = { CircularProgressIndicator() },
                confirmButton = {},
                dismissButton = {},
            )
        }
        is InstallUiState.Confirming ->
            NexoDialog(
                title = AddonInstallDialogText.TITLE,
                body =
                    "${current.manifest.name} v${current.manifest.version} · " +
                        "${current.manifest.catalogs.size} catalog(s)\n" +
                        "${AddonInstallDialogText.PUBLISHER_LABEL}: ${current.manifest.id}\n${current.url}",
                confirmText = AddonInstallDialogText.INSTALL,
                dismissText = AddonInstallDialogText.CANCEL,
                onConfirm = controller::confirm,
                onDismiss = controller::cancel,
                variant = com.nexo.ui.components.atoms.NexoDialogVariant.INFO,
            )
        is InstallUiState.Error ->
            NexoDialog(
                title = AddonInstallDialogText.ERROR_TITLE,
                body = AddonInstallDialogText.errorBody(current.code),
                confirmText = AddonInstallDialogText.OK,
                dismissText = AddonInstallDialogText.CANCEL,
                onConfirm = controller::dismissError,
                onDismiss = controller::dismissError,
                variant = com.nexo.ui.components.atoms.NexoDialogVariant.DESTRUCTIVE,
            )
    }
}
