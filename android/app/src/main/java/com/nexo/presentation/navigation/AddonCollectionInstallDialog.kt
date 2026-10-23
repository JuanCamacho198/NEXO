package com.nexo.presentation.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDialogVariant

/**
 * Plain-string copy, following the [AddonInstallDialogText] precedent: the
 * dialog constants are the copy, instead of growing the i18n MessageKey registry.
 */
internal object AddonCollectionInstallDialogText {
    const val TITLE = "Install add-on collection?"
    const val STREAM_MARKER = "stream access — asked separately"
    const val STREAM_NOTE =
        "Add-ons that offer reading links ask for your permission one by one. Installing this collection does not allow them."
    const val INSTALL = "Install all"
    const val CANCEL = "Cancel"
    const val INSTALLING_TITLE = "Installing add-on collection…"
    const val ERROR_TITLE = "Couldn't install collection"
    const val OK = "OK"

    fun skippedNote(skipped: Int): String = if (skipped == 1) "1 entry in this collection can't be imported." else "$skipped entries in this collection can't be imported."

    fun body(state: CollectionInstallUiState.Confirming): String {
        val addons =
            state.entries.joinToString("\n") { entry ->
                val stream = if (entry.requestsStream) " · $STREAM_MARKER" else ""
                "• ${entry.manifest.name} v${entry.manifest.version}$stream"
            }
        val skipped = if (state.skipped > 0) "\n${skippedNote(state.skipped)}" else ""
        val streamNote = if (state.entries.any { it.requestsStream }) "\n$STREAM_NOTE" else ""
        return "$addons$skipped$streamNote"
    }
}

/**
 * Confirmation dialog for `nexo://collection` deep links, hosted at the
 * NexoNavHost root next to [AddonInstallDialogHost]. Renders
 * [CollectionInstallUiState]: Confirming → the whole set, each addon named and
 * the ones requesting `stream` marked; Installing → busy dialog; Error → error
 * dialog. Cancel and outside-tap dismiss abort with nothing installed.
 */
@Composable
fun AddonCollectionInstallDialogHost(controller: CollectionInstallController) {
    val state by controller.state.collectAsStateWithLifecycle()
    when (val current = state) {
        CollectionInstallUiState.Idle -> Unit
        CollectionInstallUiState.Fetching,
        CollectionInstallUiState.Installing,
        -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(AddonCollectionInstallDialogText.INSTALLING_TITLE) },
                text = { CircularProgressIndicator() },
                confirmButton = {},
                dismissButton = {},
            )
        }
        is CollectionInstallUiState.Confirming ->
            NexoDialog(
                title = AddonCollectionInstallDialogText.TITLE,
                body = AddonCollectionInstallDialogText.body(current),
                confirmText = AddonCollectionInstallDialogText.INSTALL,
                dismissText = AddonCollectionInstallDialogText.CANCEL,
                onConfirm = controller::confirm,
                onDismiss = controller::cancel,
                variant = NexoDialogVariant.INFO,
            )
        is CollectionInstallUiState.Error ->
            NexoDialog(
                title = AddonCollectionInstallDialogText.ERROR_TITLE,
                body = AddonInstallDialogText.errorBody(current.code),
                confirmText = AddonCollectionInstallDialogText.OK,
                dismissText = AddonCollectionInstallDialogText.CANCEL,
                onConfirm = controller::dismissError,
                onDismiss = controller::dismissError,
                variant = NexoDialogVariant.DESTRUCTIVE,
            )
    }
}
