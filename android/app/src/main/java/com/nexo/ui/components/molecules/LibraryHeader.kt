package com.nexo.ui.components.molecules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.atoms.NexoIconButton
import com.nexo.ui.components.atoms.NexoTextField
import com.nexo.ui.icons.NexoIcons

/**
 * Top header for the library screen: the shared top-level bar with the library
 * title, the user avatar and the search/filter actions, plus an optional inline
 * search field below it.
 *
 * Everything visual lives in [NexoTopAppBar], so Library shares its bar height,
 * padding, title role and trailing-action geometry with Home and Discover
 * instead of re-deriving them. This wrapper only owns the two Library-specific
 * actions and the togglable search row.
 *
 * @param showSearch When `true`, the search field is rendered below the bar.
 *   Toggle this from the parent to show/hide search.
 * @param onSearchToggle Invoked when the user taps the search icon.
 *   Typically flips the `showSearch` state in the parent.
 * @param searchQuery Current value of the search input. Hoisted state
 *   owned by the parent ViewModel.
 * @param onSearchQueryChange Invoked on every keystroke in the search field.
 * @param onFilterToggle Invoked when the user taps the filter icon.
 *   Typically opens the [FilterBottomSheet].
 * @param avatarImageUrl Remote avatar URL. When `null`/blank, the
 *   [avatarInitials] fallback is used. Default `null`.
 * @param avatarInitials Fallback initials for the avatar circle. Default `"NP"`.
 * @param onAvatarClick Optional avatar click handler. When `null`, the
 *   avatar is non-interactive.
 * @param avatarContentDescription Optional accessibility label for the
 *   avatar (e.g. "Open account settings").
 *
 * **Visual**: [NexoTopAppBar] in [TopLevelVariant.Home]. When [showSearch] is
 * `true`, a 24dp-rounded [NexoTextField] appears below the bar with 16dp
 * horizontal padding and an 8dp gap.
 * **Behavior**: tapping the search icon toggles the inline search field on/off
 * via [onSearchToggle]. The filter icon is a separate action — it does NOT
 * toggle the search.
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
        NexoTopAppBar(
            title = stringResource(R.string.nav_library),
            variant = TopLevelVariant.Home,
            userInitial =
                avatarInitials
                    .trim()
                    .take(1)
                    .uppercase()
                    .ifEmpty { null },
            avatarImageUrl = avatarImageUrl,
            avatarContentDescription = avatarContentDescription,
            onAvatarClick = onAvatarClick,
            actions = {
                NexoIconButton(
                    icon = NexoIcons.Search,
                    contentDescription = stringResource(R.string.library_search_placeholder),
                    onClick = onSearchToggle,
                    size = NexoDimens.topBarActionTarget,
                    iconSize = NexoDimens.topBarActionIcon,
                )
                NexoIconButton(
                    icon = NexoIcons.FilterList,
                    contentDescription = stringResource(R.string.library_filter_label),
                    onClick = onFilterToggle,
                    size = NexoDimens.topBarActionTarget,
                    iconSize = NexoDimens.topBarActionIcon,
                )
            },
        )
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
