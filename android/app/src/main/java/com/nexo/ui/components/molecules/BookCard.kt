package com.nexo.ui.components.molecules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoBookCover
import com.nexo.ui.components.atoms.NexoProgressBar
import com.nexo.ui.components.atoms.NexoTypography
import com.nexo.ui.icons.NexoIcons

/**
 * Card representing a single book in the library list/grid. Shows a
 * cover image, title, author, and a reading-progress bar, with a
 * kebab-menu that opens edit/remove actions.
 *
 * @param title Book title rendered as a 2-line-clipped `titleMedium`.
 * @param author Author name rendered as a 1-line-clipped `bodyMedium`
 *   in `colorScheme.onSurfaceVariant`.
 * @param progress Reading progress in `[0f, 1f]`. Clamped by the
 *   underlying [NexoProgressBar]. Values outside this range will
 *   be clamped.
 * @param modifier Modifier applied to the outer `Box`.
 * @param onClick Invoked when the card body is tapped. The kebab icon
 *   in the top-right corner does NOT trigger this — it opens the menu.
 *   Default no-op.
 * @param onDeleteClick Invoked when the user taps "Remove from library"
 *   in the kebab menu. Default no-op.
 *
 * **Visual**: `Surface` with `shapes.medium` corners, 2dp elevation,
 * 16dp internal padding. Cover image fills the width at 140dp height;
 * progress bar fills the full width.
 * **Behavior**: tap on the card → [onClick]. Tap on the kebab icon →
 * opens a `DropdownMenu` with "Edit metadata" (currently a TODO
 * navigation stub) and "Remove from library" (calls [onDeleteClick]).
 * The kebab and card tap-targets are independent — tapping the kebab
 * does NOT trigger [onClick].
 * **Recomposition**: recomposes when `title`, `author`, `progress`,
 * or the callbacks change. Internal `showMenu` state is hoisted via
 * `remember` and does not survive recomposition.
 */
@Composable
fun BookCard(
    title: String,
    author: String,
    progress: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val progressDescription = stringResource(R.string.book_card_progress, (progress.coerceIn(0f, 1f) * 100).toInt())

    Box(modifier = modifier) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "$title, $progressDescription" }
                    .clickable(onClick = onClick),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
            ) {
                NexoBookCover(
                    title = title,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                NexoTypography(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                Spacer(modifier = Modifier.height(4.dp))
                NexoTypography(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(12.dp))
                NexoProgressBar(
                    progress = progress,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = progressDescription },
                )
            }
        }

        // Options menu button
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                imageVector = NexoIcons.MoreVert,
                contentDescription = stringResource(R.string.book_card_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Dropdown menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.book_card_edit_metadata)) },
                onClick = {
                    showMenu = false
                    // TODO: Navigate to edit screen
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.book_card_remove_from_library)) },
                onClick = {
                    showMenu = false
                    onDeleteClick()
                },
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BookCardDarkPreview() {
    NexoTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(16.dp)) {
            BookCard(
                title = "The Pragmatic Programmer",
                author = "Hunt & Thomas",
                progress = 0.65f,
                onClick = {},
                onDeleteClick = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BookCardLightPreview() {
    NexoTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(16.dp)) {
            BookCard(
                title = "The Pragmatic Programmer",
                author = "Hunt & Thomas",
                progress = 0.65f,
                onClick = {},
                onDeleteClick = {},
            )
        }
    }
}
