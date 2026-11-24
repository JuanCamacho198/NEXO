package com.nexo.ui.components.atoms

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme
import com.nexo.ui.theme.nexoColors

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
 * NEXO confirmation dialog: an optional icon tile, a bold centred title, a
 * quieter body, and two equal-width actions (tonal dismiss, filled confirm).
 *
 * Built on [Dialog] rather than `AlertDialog`: the stock layout put the actions
 * as small end-aligned buttons and the icon in a grey disc, which read as a
 * system prompt instead of part of the app. The two full-width buttons give both
 * choices a real touch target and make the primary one obvious.
 *
 * @param title Dialog title, `titleLarge` extra-bold, centred.
 * @param body Body text, `bodyMedium` in the secondary text colour, centred.
 * @param confirmText Label of the filled confirm button.
 * @param dismissText Label of the tonal dismiss button.
 * @param onConfirm Invoked when the user taps the confirm button. NOT called on
 *   outside-tap or back-press — those go through [onDismiss].
 * @param onDismiss Invoked on the dismiss button, an outside tap, or back.
 * @param modifier Modifier applied to the dialog surface.
 * @param icon Optional icon shown in a tile tinted with the action colour.
 * @param variant Drives the confirm colour and icon tint. Default [NexoDialogVariant.INFO].
 * @param confirmColor Optional override for the confirm container colour; the
 *   content colour still follows [variant].
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
    val colors = nexoColors
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
    // The window fades in on its own; the card adds a small spring so the
    // dialog arrives instead of just appearing.
    val appear = remember { Animatable(DialogEnterScale) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = appear.value
                        scaleY = appear.value
                    }.clip(DialogShape)
                    .background(colors.surface)
                    .border(1.dp, colors.borderSubtle, DialogShape)
                    .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Box(
                    modifier =
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(effectiveConfirmColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = effectiveConfirmColor,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(DialogButtonHeight),
                    shape = DialogButtonShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = colors.surfaceVariant,
                            contentColor = colors.textPrimary,
                        ),
                    elevation = null,
                ) {
                    Text(text = dismissText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f).height(DialogButtonHeight),
                    shape = DialogButtonShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = effectiveConfirmColor,
                            contentColor = effectiveContentColor,
                        ),
                ) {
                    Text(text = confirmText, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

private val DialogShape = RoundedCornerShape(28.dp)
private val DialogButtonShape = RoundedCornerShape(14.dp)
private val DialogButtonHeight = 48.dp
private const val DialogEnterScale = 0.92f

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
