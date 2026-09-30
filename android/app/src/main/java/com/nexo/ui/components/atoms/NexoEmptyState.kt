package com.nexo.ui.components.atoms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Full-screen empty state: a decorative vector icon, title, optional
 * subtitle, and an optional `action` slot. Mirrors the layout of
 * [NexoErrorState] but with a neutral icon and `onSurfaceVariant`
 * tint instead of the error color.
 *
 * @param icon Vector icon rendered above the title. Decorative —
 *   `contentDescription` is `null`.
 * @param title Headline text rendered in `titleMedium` semibold
 *   (`colorScheme` inherited from the surface).
 * @param modifier Modifier applied to the outer `Column`.
 * @param subtitle Optional secondary text rendered in `bodyMedium`
 *   `colorScheme.onSurfaceVariant`. Hidden when `null` or blank.
 * @param action Optional composable (typically a button) rendered
 *   below the subtitle with 16dp top spacing. Pass `null` to hide it.
 *
 * **Visual**: 64dp icon in `colorScheme.onSurfaceVariant`, 16dp gap,
 * title, 8dp gap + subtitle (if present), 16dp gap + action (if
 * present). All text is center-aligned.
 * **Behavior**: pure rendering. The icon is decorative — title carries
 * the meaning for assistive tech.
 * **Recomposition**: recomposes when `icon`, `title`, `subtitle`, or
 * `action` change.
 */
@Composable
fun NexoEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(modifier = Modifier.height(16.dp))
            action()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoEmptyStateDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoEmptyState(
            icon = NexoIcons.LibraryBooks,
            title = "No books yet",
            subtitle = "Import your first book to start reading",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoEmptyStateLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoEmptyState(
            icon = NexoIcons.LibraryBooks,
            title = "No books yet",
            subtitle = "Import your first book to start reading",
        )
    }
}
