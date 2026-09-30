package com.nexo.presentation.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoDimens
import com.nexo.ui.components.atoms.NexoButton
import com.nexo.ui.components.atoms.NexoButtonVariant
import com.nexo.ui.components.atoms.NexoEmptyState
import com.nexo.ui.icons.NexoIcons

@Composable
fun EmptyShelfPlaceholder(
    isImporting: Boolean,
    onImportClick: () -> Unit,
) {
    NexoEmptyState(icon = NexoIcons.LibraryBooks, title = stringResource(R.string.library_empty), subtitle = stringResource(R.string.library_import_formats), modifier = Modifier.fillMaxWidth().padding(horizontal = NexoDimens.spacingMd, vertical = NexoDimens.spacingLg), action = { NexoButton(onClick = onImportClick, enabled = !isImporting, variant = NexoButtonVariant.OUTLINED, border = BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.primary)) { Text(text = stringResource(R.string.library_import_book)) } })
}
