package com.nexo.presentation.screen.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nexo.R
import com.nexo.domain.model.Bookmark
import com.nexo.presentation.feature.highlights.HighlightsSheet
import com.nexo.presentation.feature.highlights.utils.ChapterLocation
import com.nexo.presentation.screen.PageBookmarkRibbon
import com.nexo.presentation.viewmodel.ReaderViewModel
import com.nexo.presentation.viewmodel.reader.AnnotationUiState
import com.nexo.presentation.viewmodel.reader.SearchUiState
import com.nexo.presentation.viewmodel.reader.SessionUiState
import com.nexo.presentation.viewmodel.reader.SettingsUiState
import com.nexo.presentation.viewmodel.reader.SleepTimerUiState
import com.nexo.ui.components.molecules.BookmarksSheet
import com.nexo.ui.components.molecules.ChaptersSheet
import com.nexo.ui.components.molecules.HighlightAnnotationModal
import com.nexo.ui.components.molecules.SearchBottomSheet
import com.nexo.ui.components.molecules.SleepTimerOverlay
import com.nexo.ui.components.molecules.SleepTimerPreset
import com.nexo.ui.components.molecules.SleepTimerSheet
import com.nexo.ui.components.molecules.SplitSettingsSheet

@Composable
fun ReaderScreenOverlaysHost(
    annotationUiState: AnnotationUiState,
    searchUiState: SearchUiState,
    settingsUiState: SettingsUiState,
    sleepTimerUiState: SleepTimerUiState,
    sessionUiState: SessionUiState,
    viewModel: ReaderViewModel,
    showSleepTimerSheet: Boolean,
    onDismissSleepTimerSheet: () -> Unit,
    showGoToPageDialog: Boolean,
    goToPageInput: String,
    goToPageError: String?,
    onGoToPageInputChange: (String) -> Unit,
    onGoToPageConfirm: () -> Unit,
    onDismissGoToPage: () -> Unit,
    pageBookmark: Bookmark?,
    showBookmarksSheet: Boolean,
    onDismissBookmarksSheet: () -> Unit,
    onAddBookmark: () -> Unit,
) {
    PageBookmarkRibbon(visible = pageBookmark != null)

    if (showBookmarksSheet) {
        BookmarksSheet(
            bookmarks = annotationUiState.bookmarks,
            pageBookmark = pageBookmark,
            onAddBookmark = onAddBookmark,
            onRemoveBookmark = { viewModel.deleteBookmark(it.id) },
            onBookmarkSelected = { viewModel.onBookmarkSelected(it) },
            onDismiss = onDismissBookmarksSheet,
        )
    }

    if (searchUiState.isSearchActive) {
        SearchBottomSheet(
            query = searchUiState.searchQuery,
            results = searchUiState.searchResults,
            isSearching = searchUiState.isSearching,
            onQueryChange = {
                viewModel.searchStateHolder.onSearchQuery(
                    it,
                    sessionUiState.readiumPublication,
                    sessionUiState.bookFormat,
                    sessionUiState.chapters,
                )
            },
            onClearQuery = { viewModel.onClearSearch() },
            onResultSelected = { viewModel.onSearchResultSelected(it) },
            onDismiss = { viewModel.onDismissSearch() },
        )
    }

    if (annotationUiState.showHighlightsSheet) {
        HighlightsSheet(
            highlights = annotationUiState.highlights,
            onHighlightSelected = { viewModel.onHighlightSelected(it) },
            onDismiss = { viewModel.interactionHolder.onToggleHighlightsPanel() },
            chapters = sessionUiState.chapters.map { ChapterLocation(it.index, it.title, it.href) },
        )
    }

    if (sessionUiState.showTocSheet) {
        ChaptersSheet(
            chapters = sessionUiState.chapters,
            currentChapterIndex = sessionUiState.currentChapterIndex,
            onChapterSelected = { idx -> viewModel.lifecycleHolder.goToChapter(idx) },
            onDismiss = { viewModel.lifecycleHolder.onToggleTocSheet() },
            progressPercent = sessionUiState.progressPercent,
            chapterStartFractions = sessionUiState.chapterStartFractions,
        )
    }

    AnimatedVisibility(
        visible = settingsUiState.showSplitSettings && sessionUiState.chapters.isNotEmpty(),
        enter = slideInVertically(tween(280)) { it } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
    ) {
        SplitSettingsSheet(
            settings = settingsUiState.readerSettings,
            onSettingsChanged = { viewModel.settingsManager.updateReaderSettings(it) },
            onDismiss = { viewModel.settingsManager.onDismissSplitSettings() },
        )
    }

    if (showSleepTimerSheet) {
        SleepTimerSheet(
            isActive = sleepTimerUiState.isActive,
            remainingFormatted = viewModel.sleepTimerManager.formatRemaining(sleepTimerUiState.remainingSecs),
            presets =
                listOf(
                    SleepTimerPreset("5", 5),
                    SleepTimerPreset("10", 10),
                    SleepTimerPreset("15", 15),
                    SleepTimerPreset("30", 30),
                    SleepTimerPreset(
                        label = stringResource(R.string.reader_sleep_timer_end_of_chapter),
                        minutes = Int.MIN_VALUE,
                        isEndOfChapter = true,
                    ),
                ),
            onPresetSelected = { minutes: Int ->
                viewModel.sleepTimerManager.startTimer(minutes)
                onDismissSleepTimerSheet()
            },
            onCancel = {
                viewModel.sleepTimerManager.cancel()
                onDismissSleepTimerSheet()
            },
            onDismiss = onDismissSleepTimerSheet,
        )
    }

    if (sleepTimerUiState.isFinished) {
        SleepTimerOverlay(
            onDismiss = { viewModel.sleepTimerManager.dismissOverlay() },
        )
    }

    if (annotationUiState.showNoteModal) {
        HighlightAnnotationModal(
            titleRes = R.string.note_modal_title,
            hintRes = R.string.annotation_textarea_note_hint,
            snippetLabelRes = R.string.annotation_snippet_label,
            selectedText = annotationUiState.selectedText,
            initialText = annotationUiState.activeNoteText,
            onSave = { viewModel.interactionHolder.onSaveNote(it) },
            onDismiss = { viewModel.interactionHolder.onDismissNoteModal() },
        )
    }

    if (showGoToPageDialog && sessionUiState.totalPdfPages > 0) {
        AlertDialog(
            onDismissRequest = onDismissGoToPage,
            title = { Text(text = stringResource(R.string.reader_go_to_page)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = goToPageInput,
                        onValueChange = {
                            onGoToPageInputChange(it)
                        },
                        label = { Text(text = stringResource(R.string.reader_go_to_page_input_label)) },
                        singleLine = true,
                        isError = goToPageError != null,
                        supportingText = {
                            val error = goToPageError
                            if (error != null) {
                                Text(text = error)
                            } else {
                                Text(
                                    text = stringResource(R.string.reader_go_to_page_input_hint, sessionUiState.totalPdfPages),
                                )
                            }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onGoToPageConfirm) {
                    Text(text = stringResource(R.string.reader_go))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissGoToPage) {
                    Text(text = stringResource(R.string.reader_cancel))
                }
            },
        )
    }
}
