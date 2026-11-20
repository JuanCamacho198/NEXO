package com.nexo.ui.components.molecules

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.nexo.R
import com.nexo.ui.icons.NexoIcons
import com.nexo.ui.theme.NexoTheme

/**
 * Dropdown menu of book-level actions (edit, mark completed, mark
 * plan-to-read, share, delete). Renders a Material 3 `DropdownMenu`
 * with five items; "Remove" is visually separated by a divider and
 * tinted in `colorScheme.error`.
 *
 * @param expanded Whether the menu is currently visible. Bind this to
 *   the `showMenu` state from the caller.
 * @param onDismissRequest Invoked on outside-tap, scrim-tap, or
 *   back-press. Also called by every item before its own callback so
 *   the menu always closes after a selection.
 * @param showPlanToRead When `false`, the "Plan to read" item is omitted
 *   (the Home carousel offers only Edit, Mark completed, Share, Remove).
 *   Library list/grid cards keep the default `true` and render all five.
 * @param onEdit Edit-metadata action. Called after the menu auto-dismisses.
 * @param onMarkCompleted Move the book to the "completed" status.
 * @param onMarkPlanToRead Move the book to the "plan to read" status.
 * @param onShare Share the book (open the system share sheet).
 * @param onDelete Remove the book from the library. Item label is
 *   tinted `colorScheme.error` to signal destructive intent.
 *
 * **Visual**: the shared [NexoDropdownMenu]. Items in order:
 * Edit metadata, Mark completed, Mark plan to read, Share,
 * `HorizontalDivider`, Remove. Each item shows a leading icon
 * (Pencil, Check, Star, Share) tinted `onSurfaceVariant`; the "Remove"
 * icon and label are `colorScheme.error`. Icon `contentDescription` is
 * `null` — the item text already carries the accessibility semantics.
 * **Behavior**: each item calls `onDismissRequest()` first, then its
 * specific callback. The menu is uncontrolled — visibility is fully
 * driven by [expanded].
 * **Recomposition**: recomposes when any callback or [expanded] changes.
 */
@Composable
fun BookContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    showPlanToRead: Boolean = true,
    onEdit: () -> Unit,
    onMarkCompleted: () -> Unit,
    onMarkPlanToRead: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    NexoDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        NexoDropdownMenuItem(
            text = stringResource(R.string.library_menu_edit_metadata),
            icon = NexoIcons.Pencil,
            onClick = {
                onDismissRequest()
                onEdit()
            },
        )
        NexoDropdownMenuItem(
            text = stringResource(R.string.library_menu_mark_completed),
            icon = NexoIcons.Check,
            onClick = {
                onDismissRequest()
                onMarkCompleted()
            },
        )
        if (showPlanToRead) {
            NexoDropdownMenuItem(
                text = stringResource(R.string.library_menu_mark_plan_to_read),
                icon = NexoIcons.Star,
                onClick = {
                    onDismissRequest()
                    onMarkPlanToRead()
                },
            )
        }
        NexoDropdownMenuItem(
            text = stringResource(R.string.library_menu_share),
            icon = NexoIcons.Share,
            onClick = {
                onDismissRequest()
                onShare()
            },
        )
        NexoDropdownMenuDivider()
        NexoDropdownMenuItem(
            text = stringResource(R.string.library_menu_remove),
            icon = NexoIcons.Trash,
            destructive = true,
            onClick = {
                onDismissRequest()
                onDelete()
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BookContextMenuDarkPreview() {
    NexoTheme(darkTheme = true) {
        BookContextMenu(
            expanded = true,
            onDismissRequest = {},
            onEdit = {},
            onMarkCompleted = {},
            onMarkPlanToRead = {},
            onShare = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BookContextMenuLightPreview() {
    NexoTheme(darkTheme = false) {
        BookContextMenu(
            expanded = true,
            onDismissRequest = {},
            onEdit = {},
            onMarkCompleted = {},
            onMarkPlanToRead = {},
            onShare = {},
            onDelete = {},
        )
    }
}
