package com.nexo.presentation.screen.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.feature.library.LibrarySyncStatus
import com.nexo.ui.components.molecules.LibraryHeader
import com.nexo.ui.components.molecules.SortControlRow
import com.nexo.ui.components.molecules.StatusChipRow
import com.nexo.ui.theme.NexoTheme

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
    syncError: String? = null,
    isSyncing: Boolean = false,
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
            streakContentDescription = stringResource(R.string.home_streak_badge_content_description, 0),
        )

        StatusChipRow(
            selectedTab = statusFilter,
            onTabSelected = onStatusFilterChanged,
        )

        Spacer(modifier = Modifier.height(14.dp))

        SortControlRow(
            sortBy = sortBy,
            onSortByChanged = onSortByChanged,
            isGridView = isGridView,
            onViewToggle = onViewToggle,
            trailingStatus = { LibrarySyncStatus(syncError = syncError, isSyncing = isSyncing) },
        )

        Spacer(modifier = Modifier.height(16.dp))
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
