package com.nexo.presentation.feature.highlights.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Card showing a single saved highlight: the highlight text, an
 * optional attribution (e.g. "— Chapter 3"), an optional personal
 * note, and an optional tag chip. The card has a 6dp-wide colored
 * left edge in [accentColor] and an optional kebab menu (Copy Text,
 * Edit Note, Change Color, View in Book, Add/Edit Tag, Delete).
 *
 * @param content The highlighted text. Rendered in `bodyMedium` medium
 *   weight, no line limit.
 * @param accentColor Color used for the left edge stripe.
 * @param modifier Modifier applied to the outer `Surface`.
 * @param attribution Optional source line (e.g. "— Chapter 3") shown
 *   in `bodySmall` after the content. When `null` or blank, the
 *   attribution row is hidden.
 * @param note Optional personal note. When non-blank, rendered in
 *   `bodySmall` below the attribution, capped at 3 lines with
 *   ellipsis.
 * @param tag Optional tag label rendered as a `primaryContainer`
 *   chip. When [onTagClick] is also non-null, the chip is
 *   clickable and fires it with the tag string.
 * @param onCopyText Optional copy-text action. Shown in the kebab menu.
 * @param onEditNote Optional edit-note action. Shown in the kebab menu.
 * @param onChangeColor Optional change-color action. Shown in the kebab menu.
 * @param onViewInBook Optional view-in-book action. Shown in the kebab menu.
 * @param onAddTag Optional add/edit tag action. Shown in the kebab menu.
 * @param onDelete Optional delete action. The "Delete" menu item is
 *   tinted `colorScheme.error` to signal destructive intent.
 * @param onTagClick Optional tag-chip click handler. When non-null
 *   AND [tag] is non-null, the tag chip is clickable and fires
 *   this callback with the tag string.
 *
 * **Visual**: 12dp-rounded `surfaceVariant` card with a 6dp left
 *   edge in [accentColor]. Content padded 16dp on the sides and
 *   bottom. Optional kebab icon (40dp) in the top-right corner.
 * **Behavior**: the kebab menu is shown when at least one callback
 *   is non-null. Tapping a menu item dismisses the menu and calls
 *   the respective callback. Tapping the tag chip (if [onTagClick]
 *   is wired) calls the callback with the tag string.
 * **Recomposition**: recomposes when `content`, `accentColor`, or
 *   any of the optional params/callbacks change. Internal
 *   `showMenu` state is `remember`-ed.
 */
@Composable
fun NexoHighlightCard(
    content: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    attribution: String? = null,
    note: String? = null,
    tag: String? = null,
    onCopyText: (() -> Unit)? = null,
    onEditNote: (() -> Unit)? = null,
    onChangeColor: (() -> Unit)? = null,
    onViewInBook: (() -> Unit)? = null,
    onAddTag: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onTagClick: ((String) -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    val hasMenu =
        onCopyText != null ||
            onEditNote != null ||
            onChangeColor != null ||
            onViewInBook != null ||
            onAddTag != null ||
            onDelete != null

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
        ) {
            // Colored left edge. The Row is sized with IntrinsicSize.Min so
            // fillMaxHeight() resolves to the content height — without it the
            // stripe measures 0dp tall (the Row has no fixed height) and the
            // accent color becomes invisible.
            Box(
                modifier =
                    Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                        .background(accentColor),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(16.dp),
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (tag != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val clickable = onTagClick != null
                        Box(
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .then(
                                        if (clickable) {
                                            Modifier.clickable { onTagClick.invoke(tag) }
                                        } else {
                                            Modifier
                                        },
                                    ).padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                if (!attribution.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "\u2014 $attribution",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (hasMenu) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = NexoIcons.MoreVert,
                            contentDescription = stringResource(R.string.context_menu_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        onCopyText?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.highlights_menu_copy_text)) },
                                onClick = {
                                    showMenu = false
                                    cb()
                                },
                            )
                        }
                        onEditNote?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.highlights_menu_edit_note)) },
                                onClick = {
                                    showMenu = false
                                    cb()
                                },
                            )
                        }
                        onChangeColor?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.highlights_menu_change_color)) },
                                onClick = {
                                    showMenu = false
                                    cb()
                                },
                            )
                        }
                        onViewInBook?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.highlights_menu_view_in_book)) },
                                onClick = {
                                    showMenu = false
                                    cb()
                                },
                            )
                        }
                        if (onCopyText != null || onEditNote != null || onChangeColor != null || onViewInBook != null) {
                            HorizontalDivider()
                        }
                        onAddTag?.let { cb ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.highlights_menu_add_tag)) },
                                onClick = {
                                    showMenu = false
                                    cb()
                                },
                            )
                        }
                        onDelete?.let { deleteCb ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.highlights_menu_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    deleteCb()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoHighlightCardDarkPreview() {
    NexoTheme(darkTheme = true) {
        NexoHighlightCard(
            content = "To be, or not to be, that is the question.",
            accentColor = Color(0xFFFBBF24),
            attribution = "Chapter 3",
            note = "This passage makes me think about choices.",
            tag = "Philosophy",
            onCopyText = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NexoHighlightCardLightPreview() {
    NexoTheme(darkTheme = false) {
        NexoHighlightCard(
            content = "To be, or not to be, that is the question.",
            accentColor = Color(0xFFFBBF24),
            attribution = "Chapter 3",
            note = "This passage makes me think about choices.",
            tag = "Philosophy",
            onCopyText = {},
            onDelete = {},
        )
    }
}
