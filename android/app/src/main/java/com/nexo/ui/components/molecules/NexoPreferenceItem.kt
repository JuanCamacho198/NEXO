package com.nexo.ui.components.molecules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Settings-list row: leading icon + label on the left, optional
 * value text and a right chevron on the right. Tapping the row
 * invokes [onClick] (e.g. to open a sub-page or show a dialog).
 *
 * @param icon Leading icon (24dp) rendered in `colorScheme.onSurface`.
 *   Decorative — `contentDescription` is `null`.
 * @param label Primary text rendered in `bodyLarge`
 *   `colorScheme.onSurface`.
 * @param modifier Modifier applied to the outer `Surface`.
 * @param value Optional secondary text shown before the chevron (e.g.
 *   the current value of the preference). When `null` or blank, the
 *   value column is hidden and only the chevron is shown.
 * @param onClick Invoked on tap. Default no-op.
 *
 * **Visual**: full-width `surfaceVariant` row with `shapes.medium`
 *   corners, 16dp internal padding. Left: 24dp icon + 16dp gap +
 *   label. Right: value (1-line, ellipsized) + 4dp gap + 20dp
 *   right-pointing chevron, both in `colorScheme.onSurfaceVariant`.
 * **Behavior**: tap → [onClick]. No internal state.
 * **Recomposition**: recomposes when `icon`, `label`, `value`, or
 *   `onClick` change.
 */
@Composable
fun NexoPreferenceItem(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    onClick: () -> Unit = {},
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!value.isNullOrBlank()) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = NexoIcons.ArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoPreferenceItemDarkPreview() {
    NexoTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NexoPreferenceItem(
                icon = NexoIcons.Settings,
                label = "Account",
                value = "user@example.com",
            )
            NexoPreferenceItem(
                icon = NexoIcons.Info,
                label = "About NEXO",
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoPreferenceItemLightPreview() {
    NexoTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NexoPreferenceItem(
                icon = NexoIcons.Settings,
                label = "Account",
                value = "user@example.com",
            )
            NexoPreferenceItem(
                icon = NexoIcons.Info,
                label = "About NEXO",
            )
        }
    }
}
