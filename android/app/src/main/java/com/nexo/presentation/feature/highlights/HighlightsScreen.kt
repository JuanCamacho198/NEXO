package com.nexo.presentation.feature.highlights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.Book
import com.nexo.domain.model.Highlight
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.viewmodel.HighlightsViewModel
import com.nexo.ui.components.molecules.NexoSelector
import com.nexo.ui.components.molecules.SelectorOption

@Composable
fun HighlightsScreen(
    contentPadding: PaddingValues,
    viewModel: HighlightsViewModel,
    authSession: AuthSession? = null,
    onOpenAccount: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.syncHighlights()
    }
    HighlightsScreenContent(
        uiState = uiState,
        contentPadding = contentPadding,
        authSession = authSession,
        onOpenAccount = onOpenAccount,
        syncState = syncState,
        onSyncRefresh = { viewModel.syncHighlights(force = true) },
        onBookFilterChanged = viewModel::onBookFilterChanged,
        onTagFilterChanged = viewModel::onTagFilterChanged,
        onSearchQueryChange = viewModel::onSearchQueryChanged,
        onTypeFilterChanged = viewModel::onTypeFilterChanged,
        onColorFilterChanged = viewModel::onColorFilterChanged,
        onColorFilterReset = viewModel::onColorFilterReset,
        onCopyHighlight = viewModel::onCopyHighlight,
        onEditHighlightNote = viewModel::onEditHighlightNote,
        onChangeHighlightColor = viewModel::onChangeHighlightColor,
        onViewInBook = viewModel::onViewInBook,
        onAddHighlightTag = viewModel::onAddHighlightTag,
        onDeleteHighlight = viewModel::onDeleteHighlight,
        onSaveHighlightNote = viewModel::onSaveHighlightNote,
        onDismissEditHighlight = viewModel::dismissEditHighlight,
        onDismissDeleteHighlightDialog = viewModel::dismissDeleteHighlightDialog,
        onConfirmDeleteHighlight = viewModel::confirmDeleteHighlight,
        onDismissColorPicker = viewModel::dismissColorPicker,
        onConfirmColorChange = viewModel::onConfirmColorChange,
        onDismissTagEdit = viewModel::dismissTagEdit,
        onTagEditTextChanged = viewModel::onTagEditTextChanged,
        onSaveHighlightTag = viewModel::onSaveHighlightTag,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HighlightsScreenContent(
    uiState: HighlightsUiState,
    contentPadding: PaddingValues,
    authSession: AuthSession? = null,
    onOpenAccount: () -> Unit = {},
    syncState: com.nexo.presentation.viewmodel.highlights.HighlightsSyncState = com.nexo.presentation.viewmodel.highlights.HighlightsSyncState.Idle,
    onSyncRefresh: () -> Unit = {},
    onBookFilterChanged: (String?) -> Unit,
    onTagFilterChanged: (String?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTypeFilterChanged: (String) -> Unit,
    onColorFilterChanged: (String) -> Unit,
    onColorFilterReset: () -> Unit,
    onCopyHighlight: (Highlight) -> Unit,
    onEditHighlightNote: (Highlight) -> Unit,
    onChangeHighlightColor: (Highlight) -> Unit,
    onViewInBook: (Highlight) -> Unit,
    onAddHighlightTag: (Highlight) -> Unit,
    onDeleteHighlight: (Highlight) -> Unit,
    onSaveHighlightNote: (String) -> Unit,
    onDismissEditHighlight: () -> Unit,
    onDismissDeleteHighlightDialog: () -> Unit,
    onConfirmDeleteHighlight: () -> Unit,
    onDismissColorPicker: () -> Unit,
    onConfirmColorChange: (String) -> Unit,
    onDismissTagEdit: () -> Unit,
    onTagEditTextChanged: (String) -> Unit,
    onSaveHighlightTag: (String) -> Unit,
) {
    var showSearch by remember { mutableStateOf(false) }
    var showBookSelector by remember { mutableStateOf(false) }
    var showTagSelector by remember { mutableStateOf(false) }

    val bookOptions =
        remember(uiState.books) {
            listOf(
                SelectorOption("all", R.string.highlights_filter_all_books, icon = com.nexo.ui.icons.NexoIcons.Book),
            ) + uiState.books.map { SelectorOption(it.id, labelRes = null, label = it.title) }
        }

    if (showBookSelector) {
        NexoSelector(
            title = stringResource(R.string.highlights_filter_book),
            options = bookOptions,
            selectedOptionId = uiState.bookFilter ?: "all",
            onOptionSelected = { option ->
                onBookFilterChanged(if (option.id == "all") null else option.id)
                showBookSelector = false
            },
            onDismiss = { showBookSelector = false },
        )
    }

    val tagOptions =
        remember(uiState.availableTags) {
            listOf(
                SelectorOption("all", R.string.highlights_filter_all_tags),
            ) + uiState.availableTags.map { SelectorOption(it, labelRes = null, label = it) }
        }

    if (showTagSelector) {
        NexoSelector(
            title = stringResource(R.string.highlights_filter_tag),
            options = tagOptions,
            selectedOptionId = uiState.tagFilter ?: "all",
            onOptionSelected = { option ->
                onTagFilterChanged(if (option.id == "all") null else option.id)
                showTagSelector = false
            },
            onDismiss = { showTagSelector = false },
        )
    }

    val isSyncing = syncState is com.nexo.presentation.viewmodel.highlights.HighlightsSyncState.Syncing
    val pullState = rememberPullToRefreshState()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        PullToRefreshBox(
            isRefreshing = isSyncing,
            onRefresh = onSyncRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(NexoDimens.spacingMd),
            ) {
                item {
                    HighlightsHeaderSection(
                        authSession = authSession,
                        onOpenAccount = onOpenAccount,
                        onSearchClick = { showSearch = !showSearch },
                        syncState = syncState,
                        onSyncRefresh = onSyncRefresh,
                    )
                }
                item {
                    HighlightsSearchSection(
                        showSearch = showSearch,
                        searchQuery = uiState.searchQuery,
                        onSearchQueryChange = onSearchQueryChange,
                    )
                }
                item {
                    HighlightsFilterSection(
                        uiState = uiState,
                        onTypeFilterChanged = onTypeFilterChanged,
                        onBookFilterClick = { showBookSelector = true },
                        onTagFilterClick = { showTagSelector = true },
                        onColorFilterChanged = onColorFilterChanged,
                        onColorFilterReset = onColorFilterReset,
                    )
                }
                item {
                    HighlightsListSection(
                        uiState = uiState,
                        onCopyHighlight = onCopyHighlight,
                        onEditHighlightNote = onEditHighlightNote,
                        onChangeHighlightColor = onChangeHighlightColor,
                        onViewInBook = onViewInBook,
                        onAddHighlightTag = onAddHighlightTag,
                        onDeleteHighlight = onDeleteHighlight,
                        onTagFilterChanged = onTagFilterChanged,
                    )
                }
            }
        }
    }

    HighlightsDialogs(
        uiState = uiState,
        onSaveHighlightNote = onSaveHighlightNote,
        onDismissEditHighlight = onDismissEditHighlight,
        onDismissDeleteHighlightDialog = onDismissDeleteHighlightDialog,
        onConfirmDeleteHighlight = onConfirmDeleteHighlight,
        onDismissColorPicker = onDismissColorPicker,
        onConfirmColorChange = onConfirmColorChange,
        onDismissTagEdit = onDismissTagEdit,
        onTagEditTextChanged = onTagEditTextChanged,
        onSaveHighlightTag = onSaveHighlightTag,
    )
}
