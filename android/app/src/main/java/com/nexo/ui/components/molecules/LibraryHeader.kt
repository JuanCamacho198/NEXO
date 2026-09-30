package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoAvatar
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons

/**
 * Top header for the library screen: user avatar + large title on the
 * left, search and filter icon buttons on the right, and an optional
 * inline search field that toggles in/out based on [showSearch].
 *
 * @param showSearch When `true`, the search field is rendered below
 *   the title row. Toggle this from the parent to show/hide search.
 * @param onSearchToggle Invoked when the user taps the search icon.
 *   Typically flips the `showSearch` state in the parent.
 * @param searchQuery Current value of the search input. Hoisted state
 *   owned by the parent ViewModel.
 * @param onSearchQueryChange Invoked on every keystroke in the
 *   search field.
 * @param onFilterToggle Invoked when the user taps the filter icon.
 *   Typically opens the [FilterBottomSheet].
 * @param avatarImageUrl Remote avatar URL. When `null`/blank, the
 *   [avatarInitials] fallback is used. Default `null`.
 * @param avatarInitials Fallback initials for the avatar circle.
 *   Default `"NP"`.
 * @param onAvatarClick Optional avatar click handler. When `null`, the
 *   avatar is non-interactive.
 * @param avatarContentDescription Optional accessibility label for the
 *   avatar (e.g. "Open account settings").
 *
 * **Visual**: `headlineMedium` bold title on the left, two
 * `IconButton`s (Search, FilterList) on the right, with 4dp spacing
 * between them. When [showSearch] is `true`, a 24dp-rounded
 * [NexoTextField] appears below the title with 16dp horizontal
 * padding. The whole header uses 24dp vertical padding.
 * **Behavior**: tapping the search icon toggles the inline search
 * field on/off via [onSearchToggle]. The filter icon is a separate
 * action — it does NOT toggle the search.
 * **Recomposition**: recomposes when `showSearch`, `searchQuery`, or
 * any callback changes.
 */
@Composable
fun LibraryHeader(
    showSearch: Boolean,
    onSearchToggle: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFilterToggle: () -> Unit,
    avatarImageUrl: String? = null,
    avatarInitials: String = "NP",
    onAvatarClick: (() -> Unit)? = null,
    avatarContentDescription: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NexoAvatar(
                    imageUrl = avatarImageUrl,
                    initials = avatarInitials,
                    size = 40.dp,
                    onClick = onAvatarClick,
                    contentDescription = avatarContentDescription,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.nav_library),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onSearchToggle) {
                    Icon(
                        imageVector = NexoIcons.Search,
                        contentDescription = stringResource(R.string.library_search_placeholder),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onFilterToggle) {
                    Icon(
                        imageVector = NexoIcons.FilterList,
                        contentDescription = stringResource(R.string.library_filter_label),
                    )
                }
            }
        }
        if (showSearch) {
            NexoTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(R.string.library_search_placeholder),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryHeaderDarkPreview() {
    NexoTheme(darkTheme = true) {
        LibraryHeader(
            showSearch = false,
            onSearchToggle = {},
            searchQuery = "",
            onSearchQueryChange = {},
            onFilterToggle = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryHeaderLightPreview() {
    NexoTheme(darkTheme = false) {
        LibraryHeader(
            showSearch = false,
            onSearchToggle = {},
            searchQuery = "",
            onSearchQueryChange = {},
            onFilterToggle = {},
        )
    }
}
