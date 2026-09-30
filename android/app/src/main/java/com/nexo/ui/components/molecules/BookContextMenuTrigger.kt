package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.icons.NexoIcons

/**
 * Reusable overflow-menu affordance for book cards: a MoreVert `IconButton`
 * that owns its own expanded state and renders [BookContextMenu] anchored to
 * it. Extracted so the Home carousel, Library list card, and Library grid card
 * share one implementation instead of three duplicated blocks.
 *
 * @param modifier Applied to the containing `Box` so callers can align or
 *   pad the affordance (for example `Modifier.align(Alignment.TopEnd)`).
 * @param iconTint Tint of the MoreVert icon. Defaults to
 *   `MaterialTheme.colorScheme.onSurfaceVariant`; card styles that sit on a
 *   darker surface may pass `MaterialTheme.colorScheme.onSurface`.
 * @param iconSize Size of the MoreVert icon.
 * @param buttonSize Touch target size of the icon button.
 * @param showPlanToRead Forwarded to [BookContextMenu]; when `false` the
 *   "Plan to read" item is omitted (carousel cards).
 * @param onEdit Edit-metadata action, forwarded after the menu auto-dismisses.
 * @param onMarkCompleted Move the book to the "completed" status.
 * @param onMarkPlanToRead Move the book to the "plan to read" status.
 * @param onShare Share the book (open the system share sheet).
 * @param onDelete Remove the book from the library. This is an explicit,
 *   dedicated callback; it must not be aliased to a gesture handler.
 */
@Composable
fun BookContextMenuTrigger(
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 20.dp,
    buttonSize: Dp = 36.dp,
    showPlanToRead: Boolean = true,
    onEdit: () -> Unit,
    onMarkCompleted: () -> Unit,
    onMarkPlanToRead: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.size(buttonSize),
        ) {
            Icon(
                imageVector = NexoIcons.MoreVert,
                contentDescription = stringResource(R.string.context_menu_more),
                tint = iconTint,
                modifier = Modifier.size(iconSize),
            )
        }
        BookContextMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            showPlanToRead = showPlanToRead,
            onEdit = onEdit,
            onMarkCompleted = onMarkCompleted,
            onMarkPlanToRead = onMarkPlanToRead,
            onShare = onShare,
            onDelete = onDelete,
        )
    }
}
