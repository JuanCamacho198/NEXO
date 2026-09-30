package com.nexo.presentation.screen.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.nexo.R
import com.nexo.presentation.feature.highlights.HighlightsSheet
import com.nexo.presentation.screen.BookmarkRibbonOverlay
import com.nexo.presentation.viewmodel.ReaderViewModel
import com.nexo.presentation.viewmodel.reader.AnnotationUiState
import com.nexo.presentation.viewmodel.reader.SearchUiState
import com.nexo.presentation.viewmodel.reader.SessionUiState
import com.nexo.presentation.viewmodel.reader.SettingsUiState
import com.nexo.presentation.viewmodel.reader.SleepTimerUiState
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
    bookmarkRibbonVisible: Boolean,
    onBookmarkRibbonEnd: () -> Unit,
) {
    BookmarkRibbonOverlay(
        visible = bookmarkRibbonVisible,
        onAnimationEnd = onBookmarkRibbonEnd,
    )

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
        )
    }

    if (sessionUiState.showTocSheet) {
        ChaptersSheet(
            chapters = sessionUiState.chapters,
            currentChapterIndex = sessionUiState.currentChapterIndex,
            onChapterSelected = { idx -> viewModel.lifecycleHolder.goToChapter(idx) },
            onDismiss = { viewModel.lifecycleHolder.onToggleTocSheet() },
        )
    }

    if (settingsUiState.showSplitSettings && sessionUiState.chapters.isNotEmpty()) {
        val previewText =
            remember(sessionUiState.previewText, annotationUiState.selectedText, sessionUiState.currentChapterIndex, sessionUiState.chapters) {
                sessionUiState.previewText.ifBlank {
                    annotationUiState.selectedText?.takeIf { it.isNotBlank() }
                        ?: sessionUiState.chapters.getOrNull(sessionUiState.currentChapterIndex)?.title
                        ?: ""
                }
            }
        SplitSettingsSheet(
            settings = settingsUiState.readerSettings,
            previewText = previewText,
            onSettingsChanged = { viewModel.settingsManager.updateReaderSettings(it) },
            onDismiss = { viewModel.settingsManager.onToggleSplitSettings() },
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
