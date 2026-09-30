package com.nexo.presentation.feature.highlights

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons

@Composable
fun HighlightsSearchSection(
    showSearch: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
) {
    if (showSearch) {
        NexoTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = stringResource(R.string.highlights_search),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = NexoIcons.Close,
            trailingIconContentDescription = stringResource(R.string.reader_settings_close),
        )
    }
}
