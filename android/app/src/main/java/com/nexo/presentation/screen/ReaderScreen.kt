package com.nexo.presentation.screen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.BuildConfig
import com.nexo.R
import com.nexo.debug.DebugLog
import com.nexo.debug.DebugPanel
import com.nexo.debug.DebugPrefs
import com.nexo.presentation.screen.reader.ReaderScreenContentHost
import com.nexo.presentation.screen.reader.ReaderScreenEffects
import com.nexo.presentation.screen.reader.ReaderScreenOverlaysHost
import com.nexo.presentation.screen.reader.rememberReaderSelectionCallbacks
import com.nexo.presentation.viewmodel.ReaderViewModel
import com.nexo.presentation.viewmodel.reader.ReaderSelectionState
import com.nexo.ui.components.molecules.ReadingProgressBar
import kotlinx.coroutines.flow.MutableSharedFlow

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    contentPadding: PaddingValues,
    selectedBookId: String,
    bookFilePath: String?,
    bookFormat: String = "epub",
    bookIdentitySource: String = "snapshot",
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit = {},
) {
    // SDD reader-uiState-cleanup S6: every read below uses slices; the
    // DebugPanel takes the annotation slice directly, so no uiState collect.
    val searchUiState by viewModel.searchUiState.collectAsStateWithLifecycle()
    val chromeUiState by viewModel.chromeUiState.collectAsStateWithLifecycle()
    val settingsUiState by viewModel.settingsUiState.collectAsStateWithLifecycle()
    val sleepTimerUiState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()
    val sessionUiState by viewModel.sessionUiState.collectAsStateWithLifecycle()
    val annotationUiState by viewModel.annotationUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    var showGoToPageDialog by remember { mutableStateOf(false) }
    var goToPageInput by remember { mutableStateOf("") }
    var goToPageError by remember { mutableStateOf<String?>(null) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var debugPanelVisible by remember { mutableStateOf(false) }
    var lastBookmarkTrigger by remember { mutableLongStateOf(0L) }
    var bookmarkRibbonVisible by remember { mutableStateOf(false) }

    var controlsVisible by remember(chromeUiState.isFullscreen) { mutableStateOf(true) }
    var lastInteractionAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    val isSelectionActive =
        annotationUiState.selectionState != ReaderSelectionState.None ||
            annotationUiState.showTagInput ||
            annotationUiState.showDefinitionInput ||
            annotationUiState.showColorPickerPopover

    val onShowChrome: () -> Unit = {
        if (!sessionUiState.isLoading && !isSelectionActive) {
            controlsVisible = true
            lastInteractionAt = SystemClock.elapsedRealtime()
        }
    }

    val selectionCallbacks = rememberReaderSelectionCallbacks(viewModel, context, annotationUiState)

    val inspectHighlightsHtmlTrigger = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val logWebViewTreeTrigger = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is com.nexo.presentation.UiEvent.ShowToast -> {
                    android.widget.Toast
                        .makeText(context, event.message, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
                is com.nexo.presentation.UiEvent.ShowSnackbar -> {
                    android.widget.Toast
                        .makeText(context, event.message, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
                is com.nexo.presentation.UiEvent.ShareText -> {
                    val shareIntent =
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, event.text)
                        }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, null))
                }
                else -> {}
            }
        }
    }

    ReaderScreenEffects(
        isFullscreen = chromeUiState.isFullscreen,
        selectedBookId = selectedBookId,
        bookFilePath = bookFilePath,
        bookFormat = bookFormat,
        bookIdentitySource = bookIdentitySource,
        lastInteractionAt = lastInteractionAt,
        currentChapterIndex = sessionUiState.currentChapterIndex,
        currentPdfPage = sessionUiState.currentPdfPage,
        lastBookmarkTrigger = lastBookmarkTrigger,
        viewModel = viewModel,
        view = view,
        controlsVisible = controlsVisible,
        onControlsVisibleChange = { controlsVisible = it },
        onLastInteractionChange = { lastInteractionAt = it },
        onBookmarkRibbonVisibleChange = { bookmarkRibbonVisible = it },
        onShowChrome = onShowChrome,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        ReaderChrome(
            contentPadding = contentPadding,
            controlsVisible = controlsVisible,
            header = {
                ReaderHeader(
                    chapters = sessionUiState.chapters,
                    onNavigateBack = onNavigateBack,
                    onToggleSearch = { viewModel.searchStateHolder.onToggleSearch() },
                    onToggleHighlights = { viewModel.interactionHolder.onToggleHighlightsPanel() },
                    onCreateBookmark = {
                        viewModel.createBookmarkFromCurrentPosition()
                        lastBookmarkTrigger = SystemClock.elapsedRealtime()
                    },
                    onToggleSplitSettings = { viewModel.settingsManager.onToggleSplitSettings() },
                    onToggleToc = { viewModel.lifecycleHolder.onToggleTocSheet() },
                    onToggleDebugPanel = { debugPanelVisible = !debugPanelVisible },
                )
            },
            footer = {
                ReadingProgressBar(
                    progressPercent = sessionUiState.progressPercent,
                    label = sessionUiState.progressLabel,
                    onRotateScreen = {
                        val activity = context as Activity
                        val portrait = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                        activity.requestedOrientation =
                            if (portrait) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            } else {
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            }
                    },
                    onProgressChange = { viewModel.lifecycleHolder.onProgressChange(it) },
                )
            },
            content = {
                ReaderScreenContentHost(
                    annotationUiState = annotationUiState,
                    settingsUiState = settingsUiState,
                    sessionUiState = sessionUiState,
                    viewModel = viewModel,
                    selectionCallbacks = selectionCallbacks,
                    onShowChrome = onShowChrome,
                    inspectHighlightsHtmlTrigger = inspectHighlightsHtmlTrigger,
                    logWebViewTreeTrigger = logWebViewTreeTrigger,
                    onRetry = {
                        viewModel.loadBookWithFallback(selectedBookId, bookFilePath, bookFormat)
                    },
                )
            },
            overlays = {
                ReaderScreenOverlaysHost(
                    annotationUiState = annotationUiState,
                    searchUiState = searchUiState,
                    settingsUiState = settingsUiState,
                    sleepTimerUiState = sleepTimerUiState,
                    sessionUiState = sessionUiState,
                    viewModel = viewModel,
                    showSleepTimerSheet = showSleepTimerSheet,
                    onDismissSleepTimerSheet = { showSleepTimerSheet = false },
                    showGoToPageDialog = showGoToPageDialog,
                    goToPageInput = goToPageInput,
                    goToPageError = goToPageError,
                    onGoToPageInputChange = {
                        goToPageInput = it
                        goToPageError = null
                    },
                    onGoToPageConfirm = {
                        val pageNumber = goToPageInput.toIntOrNull()
                        if (pageNumber == null || pageNumber !in 1..sessionUiState.totalPdfPages) {
                            goToPageError = "Enter a value between 1 and ${sessionUiState.totalPdfPages}"
                        } else {
                            viewModel.lifecycleHolder.goToPdfPage(pageNumber - 1)
                            showGoToPageDialog = false
                            goToPageInput = ""
                            goToPageError = null
                        }
                    },
                    onDismissGoToPage = {
                        showGoToPageDialog = false
                        goToPageInput = ""
                        goToPageError = null
                    },
                    bookmarkRibbonVisible = bookmarkRibbonVisible,
                    onBookmarkRibbonEnd = { bookmarkRibbonVisible = false },
                )
            },
        )

        if (BuildConfig.DEBUG && DebugPrefs.isEnabled(context)) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 16.dp, end = 12.dp)
                        .clickable { debugPanelVisible = !debugPanelVisible },
                color = Color(0xFFEF4444),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.debug_chip_label),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        if (BuildConfig.DEBUG && DebugPrefs.isEnabled(context)) {
            DebugPanel(
                visible = debugPanelVisible,
                annotation = annotationUiState,
                onClose = { debugPanelVisible = false },
                onForceColorPicker = { viewModel.interactionHolder.onDebugForceColorPicker() },
                onForceContextMenu = { viewModel.interactionHolder.onDebugForceMenu() },
                onSimulateHighlightTap = {
                    val first = annotationUiState.highlights.firstOrNull { it.locatorJson != null }
                    if (first == null) {
                        DebugLog.warn("Debug", "Simulate-tap: no highlights to simulate")
                    } else {
                        val fakeRect =
                            android.graphics.RectF(
                                annotationUiState.selectionRect?.left?.toFloat() ?: 200f,
                                annotationUiState.selectionRect?.top?.toFloat() ?: 200f,
                                (annotationUiState.selectionRect?.right ?: 600).toFloat(),
                                (annotationUiState.selectionRect?.bottom ?: 250).toFloat(),
                            )
                        DebugLog.info("Debug", "Simulate-tap: forcing onHighlightTapped for id=${first.id}")
                        viewModel.interactionHolder.onHighlightTapped(first, fakeRect)
                    }
                },
                onClearLog = { DebugLog.clear() },
                onCopyLog = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("debug_log", DebugLog.toText()))
                    DebugLog.success("Debug", "Log copied to clipboard")
                },
                onInspectHighlightsHtml = { inspectHighlightsHtmlTrigger.tryEmit(Unit) },
                onLogWebViewTree = { logWebViewTreeTrigger.tryEmit(Unit) },
            )
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
