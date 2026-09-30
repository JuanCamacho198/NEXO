package com.nexo.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Circular, filled-background icon button. Lightweight alternative to
 * Material 3 `IconButton`/`IconToggleButton` that exposes explicit
 * container/icon sizing and colors.
 *
 * @param icon Vector to render inside the circle.
 * @param contentDescription Accessibility label for the icon. Required by
 *   TalkBack; pass an empty string (`""`) only for purely decorative icons.
 * @param onClick Invoked on tap.
 * @param modifier Modifier applied to the outer `Box`.
 * @param size Diameter of the circular touch target. Default `40.dp`.
 * @param iconSize Side length of the inner `Icon`. Should be ≤ [size].
 * @param containerColor Fill color of the circle. Default
 *   `colorScheme.surfaceVariant`.
 * @param iconTint Tint applied to the icon. Default
 *   `colorScheme.onSurface`.
 *
 * **Visual**: solid filled circle (clipped to `CircleShape`) with the
 * icon centered. No ripple drawable — the click is wired via
 * `Modifier.clickable` with the default `LocalIndication`, so an
 * indication overlay is shown on top.
 * **Behavior**: `clickable` consumes the tap and invokes [onClick]. No
 * `enabled` flag — disable at the call site by no-op-ing [onClick] or
 * wrapping in an `if`.
 * **Recomposition**: recomposes when `icon`, `onClick`, `size`,
 * `iconSize`, `containerColor`, or `iconTint` change.
 */
@Composable
fun NexoIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Preview
@Composable
private fun NexoIconButtonDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoIconButton(
            icon = NexoIcons.Add,
            contentDescription = "Add",
            onClick = {},
        )
    }
}

@Preview
@Composable
private fun NexoIconButtonLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoIconButton(
            icon = NexoIcons.Add,
            contentDescription = "Add",
            onClick = {},
        )
    }
}
