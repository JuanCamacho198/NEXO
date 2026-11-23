package com.nexo.presentation.feature.highlights

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    AnimatedVisibility(visible = showSearch, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        NexoTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            // A placeholder is not an accessible name and vanishes on first
            // keystroke, so the field is labelled through its leading icon.
            leadingIcon = NexoIcons.Search,
            leadingIconContentDescription = stringResource(R.string.highlights_search),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = HighlightsHorizontalInset),
            trailingIcon = NexoIcons.Close,
            trailingIconContentDescription = stringResource(R.string.reader_settings_close),
        )
    }
}
