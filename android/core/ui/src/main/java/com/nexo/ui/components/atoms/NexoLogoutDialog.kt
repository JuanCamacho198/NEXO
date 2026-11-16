package com.nexo.ui.components.atoms

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

/**
 * Convenience wrapper around [NexoDialog] with
 * [NexoDialogVariant.DESTRUCTIVE] and a [NexoIcons.SignOut] icon,
 * pre-configured for logout confirmation.
 *
 * Copy is passed in rather than resolved here: :core:ui declares no string
 * resources, so the caller supplies its own `stringResource(...)` values.
 *
 * @param title Dialog heading.
 * @param body Explanatory body copy.
 * @param confirmText Label of the destructive confirm action.
 * @param dismissText Label of the dismiss action.
 * @param onConfirm Invoked when the user confirms logout.
 * @param onDismiss Invoked when the user dismisses the dialog.
 */
@Composable
fun NexoLogoutDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    NexoDialog(
        icon = NexoIcons.SignOut,
        variant = NexoDialogVariant.DESTRUCTIVE,
        title = title,
        body = body,
        confirmText = confirmText,
        dismissText = dismissText,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Preview(showBackground = true)
@Composable
private fun NexoLogoutDialogDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoLogoutDialog(
            title = "Log out?",
            body = "Your local library stays on this device.",
            confirmText = "Log out",
            dismissText = "Cancel",
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
            title = "Log out?",
            body = "Your local library stays on this device.",
            confirmText = "Log out",
            dismissText = "Cancel",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
