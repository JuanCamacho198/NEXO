package com.nexo.ui.components.molecules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.icons.NexoIcons

/**
 * Editable multi-value chips for the edit-metadata screen: genre chips (colored
 * dot + close) and tag chips (close only), plus an "Add…" chip that opens a
 * text dialog (REQ-edit-screen-6/7). Sanitization and the 5/10 caps live in the
 * ViewModel; [max] only hides the add chip when the list is full.
 *
 * The close icon is an independent [IconButton] so removal is a distinct,
 * accessible affordance (vs. the whole chip being clickable).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreChips(
    genres: List<String>,
    max: Int,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EditableChipRow(
        items = genres,
        max = max,
        addLabel = stringResource(R.string.edit_metadata_add_genre),
        showColorDot = true,
        onAdd = onAdd,
        onRemove = onRemove,
        modifier = modifier,
    )
}

/**
 * Tag chips: close + "Add tag", same interaction as [GenreChips] without the
 * colored dot (REQ-edit-screen-7).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(
    tags: List<String>,
    max: Int,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EditableChipRow(
        items = tags,
        max = max,
        addLabel = stringResource(R.string.edit_metadata_add_tag),
        showColorDot = false,
        onAdd = onAdd,
        onRemove = onRemove,
        modifier = modifier,
    )
}

/**
 * Read-only genre chips for the book detail screen (design b3LCZx,
 * REQ-detail-screen-5): same Surface-based chip visuals as [GenreChips]
 * (colored dot + label) without the close/add affordances.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreChipsDisplay(
    genres: List<String>,
    modifier: Modifier = Modifier,
) {
    ChipRowDisplay(
        items = genres,
        showColorDot = true,
        modifier = modifier,
    )
}

/**
 * Read-only tag chips for the book detail screen (design b3LCZx): same visuals
 * as [TagChips] without the close/add affordances.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipsDisplay(
    tags: List<String>,
    modifier: Modifier = Modifier,
) {
    ChipRowDisplay(
        items = tags,
        showColorDot = false,
        modifier = modifier,
    )
}

/** Shared read-only chip row used by [GenreChipsDisplay] and [TagChipsDisplay]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRowDisplay(
    items: List<String>,
    showColorDot: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp),
                ) {
                    if (showColorDot) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditableChipRow(
    items: List<String>,
    max: Int,
    addLabel: String,
    showColorDot: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val removeLabel = stringResource(R.string.edit_metadata_remove_chip)

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                ) {
                    if (showColorDot) {
                        Box(
                            modifier =
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = item,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    IconButton(
                        onClick = { onRemove(item) },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = NexoIcons.Close,
                            contentDescription = removeLabel,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        if (items.size < max) {
            Surface(
                onClick = {
                    draft = ""
                    showAddDialog = true
                },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = addLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(text = addLabel) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(text = addLabel) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAdd(draft)
                        draft = ""
                        showAddDialog = false
                    },
                ) {
                    Text(text = stringResource(R.string.edit_metadata_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun MetadataChipsDarkPreview() {
    NexoTheme(darkTheme = true) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GenreChips(genres = listOf("Fiction", "Adventure", "Classics"), max = 5, onAdd = {}, onRemove = {})
            TagChips(tags = listOf("favorites", "read-later"), max = 10, onAdd = {}, onRemove = {})
            GenreChipsDisplay(genres = listOf("Desarrollo personal", "Productividad"))
            TagChipsDisplay(tags = listOf("favorites"))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MetadataChipsLightPreview() {
    NexoTheme(darkTheme = false) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GenreChips(genres = listOf("Fiction", "Adventure"), max = 5, onAdd = {}, onRemove = {})
            TagChips(tags = listOf("favorites"), max = 10, onAdd = {}, onRemove = {})
        }
    }
}
