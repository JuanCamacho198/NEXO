package com.nexo.ui.components.atoms

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Convenience wrapper around [NexoDialog] with
 * [NexoDialogVariant.DESTRUCTIVE] and a [NexoIcons.SignOut] icon,
 * pre-configured for logout confirmation.
 *
 * @param onConfirm Invoked when the user confirms logout.
 * @param onDismiss Invoked when the user dismisses the dialog.
 */
@Composable
fun NexoLogoutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    NexoDialog(
        icon = NexoIcons.SignOut,
        variant = NexoDialogVariant.DESTRUCTIVE,
        title = stringResource(R.string.settings_logout_title),
        body = stringResource(R.string.settings_logout_message),
        confirmText = stringResource(R.string.settings_logout_confirm),
        dismissText = stringResource(R.string.reader_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Preview(showBackground = true)
@Composable
private fun NexoLogoutDialogDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoLogoutDialog(
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoLogoutDialogLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoLogoutDialog(
            onConfirm = {},
            onDismiss = {},
        )
    }
}
