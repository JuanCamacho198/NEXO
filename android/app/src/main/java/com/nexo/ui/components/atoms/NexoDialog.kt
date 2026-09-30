package com.nexo.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Visual variant for [NexoDialog] that drives the confirm button
 * color and icon tint automatically.
 */
enum class NexoDialogVariant {
    /** Default variant — uses [MaterialTheme.colorScheme.primary]. */
    INFO,

    /** Destructive action variant — uses [MaterialTheme.colorScheme.error]. */
    DESTRUCTIVE,

    /** Success/positive variant — uses [MaterialTheme.colorScheme.primary]. */
    SUCCESS,
}

/**
 * Material 3 `AlertDialog` with a fixed two-button layout
 * (filled confirm + dismiss `TextButton`).
 *
 * @param title Dialog title rendered as Material 3 default `headlineSmall`
 *   (the `AlertDialog` title slot).
 * @param body Dialog body text rendered in the `AlertDialog` text slot
 *   with Material 3 default body styling.
 * @param confirmText Label of the confirm [Button].
 * @param dismissText Label of the dismiss `TextButton`.
 * @param onConfirm Invoked when the user taps the confirm button. NOT
 *   called on outside-tap or back-press — those go through [onDismiss].
 * @param onDismiss Invoked when the user taps the dismiss button,
 *   taps outside the dialog, or presses back. Use this to clear the
 *   dialog state in the ViewModel.
 * @param modifier Modifier applied to the underlying `AlertDialog`.
 * @param icon Optional icon rendered centered above the title inside a
 *   circular `surfaceVariant` container. Tint follows [variant] unless
 *   [confirmColor] is explicitly set.
 * @param variant Controls the confirm button color/content and icon tint.
 *   Default [NexoDialogVariant.INFO].
 * @param confirmColor Optional explicit color override for the confirm
 *   button container. When provided, takes precedence over [variant] for
 *   the container color; the content color still follows [variant].
 *
 * **Visual**: rounded `AlertDialog` with `extraLarge` corners; when [icon]
 * is set it renders inside a circular `surfaceVariant` container above the
 * title. The confirm action is a filled [Button] in the variant color with
 * `onPrimary`/`onError` contrast; the dismiss action is a `TextButton`.
 * **Behavior**: non-cancelable programmatically — the dialog can only
 * be dismissed via [onConfirm] or [onDismiss].
 * **Recomposition**: recomposes when any parameter changes.
 */
@Composable
fun NexoDialog(
    title: String,
    body: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    variant: NexoDialogVariant = NexoDialogVariant.INFO,
    confirmColor: Color? = null,
) {
    val effectiveConfirmColor =
        confirmColor ?: when (variant) {
            NexoDialogVariant.INFO -> MaterialTheme.colorScheme.primary
            NexoDialogVariant.DESTRUCTIVE -> MaterialTheme.colorScheme.error
            NexoDialogVariant.SUCCESS -> MaterialTheme.colorScheme.primary
        }
    val effectiveContentColor =
        when (variant) {
            NexoDialogVariant.INFO -> MaterialTheme.colorScheme.onPrimary
            NexoDialogVariant.DESTRUCTIVE -> MaterialTheme.colorScheme.onError
            NexoDialogVariant.SUCCESS -> MaterialTheme.colorScheme.onPrimary
        }

    AlertDialog(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onDismiss,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) {
                    Box(
                        modifier =
                            Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = effectiveConfirmColor,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(text = title)
            }
        },
        text = { Text(text = body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = effectiveConfirmColor,
                        contentColor = effectiveContentColor,
                    ),
            ) {
                Text(text = confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = dismissText)
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun NexoDialogDestructiveDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoDialog(
            title = "Delete book?",
            body = "This removes it from your library.",
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = {},
            onDismiss = {},
            icon = NexoIcons.Trash,
            variant = NexoDialogVariant.DESTRUCTIVE,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoDialogDestructiveLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoDialog(
            title = "Delete book?",
            body = "This removes it from your library.",
            confirmText = "Delete",
            dismissText = "Cancel",
            onConfirm = {},
            onDismiss = {},
            icon = NexoIcons.Trash,
            variant = NexoDialogVariant.DESTRUCTIVE,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoDialogInfoDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoDialog(
            title = "Info",
            body = "This is an informational message.",
            confirmText = "OK",
            dismissText = "Close",
            onConfirm = {},
            onDismiss = {},
            icon = NexoIcons.Info,
            variant = NexoDialogVariant.INFO,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoDialogInfoLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoDialog(
            title = "Info",
            body = "This is an informational message.",
            confirmText = "OK",
            dismissText = "Close",
            onConfirm = {},
            onDismiss = {},
            icon = NexoIcons.Info,
            variant = NexoDialogVariant.INFO,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoDialogSuccessDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoDialog(
            title = "Saved",
            body = "Your changes were saved.",
            confirmText = "Great",
            dismissText = "Close",
            onConfirm = {},
            onDismiss = {},
            icon = NexoIcons.Check,
            variant = NexoDialogVariant.SUCCESS,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoDialogSuccessLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoDialog(
            title = "Saved",
            body = "Your changes were saved.",
            confirmText = "Great",
            dismissText = "Close",
            onConfirm = {},
            onDismiss = {},
            icon = NexoIcons.Check,
            variant = NexoDialogVariant.SUCCESS,
        )
    }
}
