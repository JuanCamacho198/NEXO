package com.nexo.presentation.screen.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nexo.R
import com.nexo.presentation.screen.ReadiumPdfReaderContent
import com.nexo.presentation.screen.ReadiumReaderContent
import com.nexo.presentation.screen.readium.buildNavigatorConfig
import com.nexo.presentation.viewmodel.ReaderViewModel
import com.nexo.presentation.viewmodel.reader.AnnotationUiState
import com.nexo.presentation.viewmodel.reader.SessionUiState
import com.nexo.presentation.viewmodel.reader.SettingsUiState
import com.nexo.ui.components.molecules.SelectionOverlay
import kotlinx.coroutines.flow.MutableSharedFlow

@Composable
fun ReaderScreenContentHost(
    annotationUiState: AnnotationUiState,
    settingsUiState: SettingsUiState,
    sessionUiState: SessionUiState,
    viewModel: ReaderViewModel,
    selectionCallbacks: ReaderSelectionCallbacks,
    onShowChrome: () -> Unit,
    inspectHighlightsHtmlTrigger: MutableSharedFlow<Unit>,
    logWebViewTreeTrigger: MutableSharedFlow<Unit>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnShowChrome by rememberUpdatedState(onShowChrome)

    val error = sessionUiState.error
    val readiumPublication = sessionUiState.readiumPublication

    @Composable
    fun SharedSelectionOverlay() {
        SelectionOverlay(
            selectionState = annotationUiState.selectionState,
            showColorPickerPopover = annotationUiState.showColorPickerPopover,
            showTagInput = annotationUiState.showTagInput,
            tagSuggestions = annotationUiState.tagSuggestions,
            activeTagText = annotationUiState.activeTagText,
            showDefinitionInput = annotationUiState.showDefinitionInput,
            activeDefinitionText = annotationUiState.activeDefinitionText,
            selectionRect = annotationUiState.selectionRect,
            selectedText = annotationUiState.selectedText,
            highlights = annotationUiState.highlights,
            activeHighlightColor = selectionCallbacks.activeOverlayHighlightColor.value,
            customHighlightColors = settingsUiState.readerSettings.customHighlightColors,
            onColorSelected = selectionCallbacks.onColorSelected,
            onCopy = selectionCallbacks.onCopy,
            onDismissContextMenu = selectionCallbacks.onDismiss,
            onDelete = selectionCallbacks.onDelete,
            onAddTag = selectionCallbacks.onAddTag,
            onAnnotate = selectionCallbacks.onAnnotate,
            onShare = selectionCallbacks.onShare,
            onDictionary = selectionCallbacks.onDictionary,
            onShowColorPickerPopover = selectionCallbacks.onShowColorPicker,
            onDismissColorPickerPopover = selectionCallbacks.onDismissColorPicker,
            onTagTextChanged = selectionCallbacks.onTagTextChanged,
            onSaveTag = selectionCallbacks.onSaveTag,
            onDismissTagInput = selectionCallbacks.onDismissTag,
            onDefinitionTextChanged = selectionCallbacks.onDefinitionTextChanged,
            onSaveDefinition = selectionCallbacks.onSaveDefinition,
            onDismissDefinitionInput = selectionCallbacks.onDismissDefinition,
        )
    }

    when {
        sessionUiState.isLoading -> {
            LoadingContent(loadTimeMs = sessionUiState.loadTimeMs, modifier = modifier)
        }
        error != null -> {
            ErrorContent(error = error, onRetry = onRetry, modifier = modifier)
        }
        sessionUiState.bookFormat == "pdf" && readiumPublication != null -> {
            Box(modifier = modifier.fillMaxSize()) {
                ReadiumPdfReaderContent(
                    publication = readiumPublication,
                    highlights = annotationUiState.highlights,
                    readerSettings = settingsUiState.readerSettings,
                    viewModel = viewModel,
                    onShowChrome = { currentOnShowChrome() },
                    modifier = Modifier.fillMaxSize(),
                )
                SharedSelectionOverlay()
            }
        }
        sessionUiState.chapters.isNotEmpty() && readiumPublication != null -> {
            Box(modifier = modifier.fillMaxSize()) {
                ReadiumReaderContent(
                    publication = readiumPublication,
                    navigatorConfig = buildNavigatorConfig(settingsUiState.readerSettings),
                    highlights = annotationUiState.highlights,
                    readerSettings = settingsUiState.readerSettings,
                    viewModel = viewModel,
                    initialLocator = sessionUiState.readiumLocator,
                    inspectHighlightsHtmlTrigger = inspectHighlightsHtmlTrigger,
                    logWebViewTreeTrigger = logWebViewTreeTrigger,
                    onShowChrome = { currentOnShowChrome() },
                    modifier = Modifier.fillMaxSize(),
                )
                SharedSelectionOverlay()
            }
        }
        else -> {
            ErrorContent(
                error = error ?: stringResource(R.string.error_unknown),
                onRetry = onRetry,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun LoadingContent(
    loadTimeMs: Long?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Color(0xFFADC6FF))
        if (loadTimeMs != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.reader_loaded_in, loadTimeMs),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF718096),
            )
        }
    }
}

@Composable
private fun ErrorContent(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFEF4444),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.reader_retry))
        }
    }
}
