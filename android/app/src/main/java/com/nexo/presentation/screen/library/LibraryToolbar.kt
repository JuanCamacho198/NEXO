package com.nexo.presentation.screen.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.presentation.theme.NexoTheme
import com.nexo.ui.components.molecules.LibraryHeader
import com.nexo.ui.components.molecules.SortControlRow
import com.nexo.ui.components.molecules.StatusChipRow

@Composable
fun LibraryToolbar(
    showSearch: Boolean,
    onSearchToggle: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onFilterToggle: () -> Unit,
    statusFilter: String,
    onStatusFilterChanged: (String) -> Unit,
    sortBy: String,
    onSortByChanged: (String) -> Unit,
    isGridView: Boolean,
    onViewToggle: () -> Unit,
    avatarImageUrl: String? = null,
    avatarInitials: String = "NP",
    onAvatarClick: (() -> Unit)? = null,
    avatarContentDescription: String? = null,
) {
    Column {
        LibraryHeader(
            showSearch = showSearch,
            onSearchToggle = onSearchToggle,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onFilterToggle = onFilterToggle,
            avatarImageUrl = avatarImageUrl,
            avatarInitials = avatarInitials,
            onAvatarClick = onAvatarClick,
            avatarContentDescription = avatarContentDescription,
        )

        StatusChipRow(
            selectedTab = statusFilter,
            onTabSelected = onStatusFilterChanged,
        )

        Spacer(modifier = Modifier.height(12.dp))

        SortControlRow(
            sortBy = sortBy,
            onSortByChanged = onSortByChanged,
            isGridView = isGridView,
            onViewToggle = onViewToggle,
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// ─── Previews ─────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun LibraryToolbarDarkPreview() {
    NexoTheme(darkTheme = true) {
        LibraryToolbar(
            showSearch = false,
            onSearchToggle = {},
            searchQuery = "",
            onSearchQueryChange = {},
            onFilterToggle = {},
            statusFilter = "all",
            onStatusFilterChanged = {},
            sortBy = "recent",
            onSortByChanged = {},
            isGridView = true,
            onViewToggle = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryToolbarLightPreview() {
    NexoTheme(darkTheme = false) {
        LibraryToolbar(
            showSearch = false,
            onSearchToggle = {},
            searchQuery = "",
            onSearchQueryChange = {},
            onFilterToggle = {},
            statusFilter = "all",
            onStatusFilterChanged = {},
            sortBy = "recent",
            onSortByChanged = {},
            isGridView = true,
            onViewToggle = {},
        )
    }
}
