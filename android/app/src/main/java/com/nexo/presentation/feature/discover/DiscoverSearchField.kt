package com.nexo.presentation.feature.discover

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoColors
import com.nexo.ui.icons.NexoIcons

/**
 * Discover search field: 48dp surface field with a search icon that tints
 * `primary` while a fetch is in flight. While searching, the trailing slot
 * shows a 16dp spinner; otherwise a clear button appears when text is present.
 *
 * Implemented directly on Material 3 [OutlinedTextField] because the app's
 * [com.nexo.ui.components.atoms.NexoTextField] atom only accepts an
 * `ImageVector` trailing slot and a fixed icon tint, so it cannot host the
 * spinner or the in-flight `primary` leading tint required by the design.
 */
@Composable
fun DiscoverSearchField(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp),
        singleLine = true,
        shape = shape,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexoColors.textPrimary),
        placeholder = {
            Text(
                text = stringResource(R.string.discover_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = NexoColors.textSecondary,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = NexoIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSearching) NexoColors.primary else NexoColors.textSecondary,
            )
        },
        trailingIcon = {
            when {
                isSearching ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = NexoColors.primary,
                    )
                query.isNotEmpty() ->
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = NexoIcons.Close,
                            contentDescription = stringResource(R.string.discover_clear_content_desc),
                            modifier = Modifier.size(16.dp),
                            tint = NexoColors.textSecondary,
                        )
                    }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = NexoColors.surface,
                unfocusedContainerColor = NexoColors.surface,
                focusedBorderColor = NexoColors.primary,
                unfocusedBorderColor = NexoColors.borderSubtle,
                cursorColor = NexoColors.primary,
            ),
    )
}
