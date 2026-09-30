package com.nexo.presentation.feature.library

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.BuildConfig
import com.nexo.R
import com.nexo.data.remote.drive.DriveAuthResult
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.Book
import com.nexo.presentation.screen.library.BookGridSection
import com.nexo.presentation.screen.library.FilterSheetContent
import com.nexo.presentation.screen.library.LibraryToolbar
import com.nexo.presentation.screen.library.RemoveBookDialog
import com.nexo.presentation.util.getContentDisplayName
import com.nexo.presentation.viewmodel.DownloadState
import com.nexo.presentation.viewmodel.LibraryUiState
import com.nexo.presentation.viewmodel.LibraryViewModel
import com.nexo.ui.components.atoms.NexoDialog
import com.nexo.ui.components.atoms.NexoDownloadOverlay
import com.nexo.ui.components.molecules.BookGridSkeleton
import com.nexo.ui.components.molecules.BookListSkeleton
import com.nexo.ui.icons.NexoIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Content the Library shelf grid/list renders when there are no visible books.
 *
 * Pure and JVM-testable so the loading gate and the post-filter empty gate
 * (LS4-LS5) are verifiable without a device. The empty placeholder keys off the
 * **searched/filtered** list, never the unfiltered `books`.
 */
internal enum class LibraryEmptyRenderState { SKELETON, EMPTY, NONE }

/**
 * @return [LibraryEmptyRenderState.SKELETON] while [isLoading], [LibraryEmptyRenderState.EMPTY]
 *   when loading finished with no searched book, otherwise [LibraryEmptyRenderState.NONE].
 */
internal fun libraryEmptyRenderState(
    isLoading: Boolean,
    isSearchedEmpty: Boolean,
): LibraryEmptyRenderState =
    when {
        isLoading -> LibraryEmptyRenderState.SKELETON
        isSearchedEmpty -> LibraryEmptyRenderState.EMPTY
        else -> LibraryEmptyRenderState.NONE
    }

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    viewModel: LibraryViewModel,
    driveAuthHelper: GoogleDriveAuthHelper,
    authSession: AuthSession?,
    onOpenAccount: () -> Unit,
    onBookSelected: (String, String, String) -> Unit,
    onEditBook: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchedBooks by viewModel.searchedBooks.collectAsStateWithLifecycle()
    val firstDownloadError by viewModel.firstDownloadError.collectAsStateWithLifecycle()
    LibraryScreenContent(uiState = uiState, searchedBooks = searchedBooks, firstDownloadError = firstDownloadError, contentPadding = contentPadding, driveAuthHelper = driveAuthHelper, authSession = authSession, onOpenAccount = onOpenAccount, onBookSelected = onBookSelected, onEditBook = onEditBook, onRefresh = viewModel::onPullToRefresh, onSearchToggle = viewModel::onToggleSearch, onSearchQueryChange = viewModel::onSearchQueryChanged, onFilterToggle = viewModel::onToggleFilterSheet, onStatusFilterChanged = viewModel::onStatusFilterChanged, onSortByChanged = viewModel::onSortByChanged, onViewToggle = viewModel::onToggleView, onRequestDeleteBook = viewModel::requestDeleteBook, onMarkCompleted = viewModel::onMenuMarkCompleted, onMarkPlanToRead = viewModel::onMenuMarkPlanToRead, onShare = viewModel::onMenuShare, onDownload = viewModel::downloadBook, onDismissDownloadError = viewModel::dismissDownloadError, onDismissDelete = viewModel::dismissDeleteDialog, onConfirmDelete = viewModel::confirmDeleteBook, onConfirmLocalOnly = viewModel::confirmDeleteLocalOnly, onConfirmLocalAndDrive = viewModel::confirmDeleteLocalAndDrive, onFormatSelected = viewModel::onFilterFormatChanged, onImportPdf = viewModel::importPdfBook, onImportEpub = viewModel::importBookFromEpub)
}

@Composable
fun LibraryScreenContent(
    uiState: LibraryUiState,
    searchedBooks: List<Book>,
    firstDownloadError: DownloadState.Error?,
    contentPadding: PaddingValues,
    driveAuthHelper: GoogleDriveAuthHelper?,
    authSession: AuthSession?,
    onOpenAccount: () -> Unit,
    onBookSelected: (String, String, String) -> Unit,
    onEditBook: (String) -> Unit,
    onRefresh: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFilterToggle: () -> Unit,
    onStatusFilterChanged: (String) -> Unit,
    onSortByChanged: (String) -> Unit,
    onViewToggle: () -> Unit,
    onRequestDeleteBook: (Book) -> Unit,
    onMarkCompleted: (Book) -> Unit,
    onMarkPlanToRead: (Book) -> Unit,
    onShare: (Book) -> Unit,
    onDownload: (String) -> Unit,
    onDismissDownloadError: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onConfirmLocalOnly: () -> Unit,
    onConfirmLocalAndDrive: () -> Unit,
    onFormatSelected: (String) -> Unit,
    onImportPdf: (sourcePath: String, fallbackTitle: String?, pdfFile: File) -> Unit,
    onImportEpub: (sourcePath: String, fallbackTitle: String?, inputStreamProvider: suspend () -> InputStream?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDriveConnectDialog by remember { mutableStateOf(false) }
    var pendingDownloadId by remember { mutableStateOf<String?>(null) }
    var isAuthorizingDrive by remember { mutableStateOf(false) }
    val driveOauthErrorText = stringResource(R.string.settings_drive_error_oauth)
    val driveConfigErrorText = stringResource(R.string.settings_drive_error_config)
    val driveAuthLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode != Activity.RESULT_OK) isAuthorizingDrive = false }
    LaunchedEffect(driveAuthHelper) {
        val helper = driveAuthHelper ?: return@LaunchedEffect
        helper.authResult.collect { result ->
            if (result != null) {
                isAuthorizingDrive = false
                when (result) {
                    is DriveAuthResult.Success -> {
                        val bookId = pendingDownloadId
                        pendingDownloadId = null
                        if (bookId != null) onDownload(bookId)
                    }
                    is DriveAuthResult.Failure -> Toast.makeText(context, driveOauthErrorText, Toast.LENGTH_SHORT).show()
                    DriveAuthResult.Canceled -> Unit
                }
                helper.consumeResult()
            }
        }
    }
    val importLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument(), onResult = { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    val fileName = getContentDisplayName(context, uri) ?: uri.lastPathSegment ?: "imported_${System.currentTimeMillis()}"
                    val mimeType = context.contentResolver.getType(uri) ?: ""
                    val isPdf = fileName.endsWith(".pdf", true) || mimeType == "application/pdf"
                    val isEpub = fileName.endsWith(".epub", true) || mimeType == "application/epub+zip"
                    if (!isPdf && !isEpub) return@runCatching
                    if (isPdf) {
                        val pdfDir = File(context.filesDir, "pdfs")
                        if (!pdfDir.exists()) pdfDir.mkdirs()
                        val pdfFile = File(pdfDir, fileName)
                        withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input -> pdfFile.outputStream().use { output -> input.copyTo(output) } } }
                        onImportPdf(pdfFile.absolutePath, fileName.removeSuffix(".pdf"), pdfFile)
                    } else {
                        val epubDir = File(context.filesDir, "epubs")
                        if (!epubDir.exists()) epubDir.mkdirs()
                        val epubFile = File(epubDir, fileName)
                        withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { input -> epubFile.outputStream().use { output -> input.copyTo(output) } } }
                        onImportEpub(epubFile.absolutePath, fileName.removeSuffix(".epub"), { epubFile.inputStream() })
                    }
                }.onFailure { }
            }
        })
    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = uiState.isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
                LibraryToolbar(showSearch = uiState.showSearch, onSearchToggle = onSearchToggle, searchQuery = uiState.searchQuery, onSearchQueryChange = onSearchQueryChange, onFilterToggle = onFilterToggle, statusFilter = uiState.statusFilter, onStatusFilterChanged = onStatusFilterChanged, sortBy = uiState.sortBy, onSortByChanged = onSortByChanged, isGridView = uiState.isGridView, onViewToggle = onViewToggle, avatarImageUrl = authSession?.photoUrl, avatarInitials = authSession?.displayName?.take(2)?.uppercase() ?: "NP", onAvatarClick = onOpenAccount, avatarContentDescription = stringResource(R.string.home_avatar_content_description))
                BookGridSection(
                    books = searchedBooks,
                    readingMinutesByBook = uiState.readingMinutesByBook,
                    progressPercentByBook = uiState.progressPercentByBook,
                    isGridView = uiState.isGridView,
                    onBookSelected = onBookSelected,
                    onDelete = onRequestDeleteBook,
                    onImportClick = { importLauncher.launch(arrayOf("application/epub+zip", "application/pdf")) },
                    onEdit = { book -> onEditBook(book.id) },
                    onMarkCompleted = onMarkCompleted,
                    onMarkPlanToRead = onMarkPlanToRead,
                    onShare = onShare,
                    emptyContent =
                        when (libraryEmptyRenderState(uiState.isLoading, searchedBooks.isEmpty())) {
                            LibraryEmptyRenderState.SKELETON -> {
                                { if (uiState.isGridView) BookGridSkeleton() else BookListSkeleton() }
                            }
                            LibraryEmptyRenderState.EMPTY -> {
                                { EmptyShelfPlaceholder(isImporting = uiState.isImporting, onImportClick = { importLauncher.launch(arrayOf("application/epub+zip", "application/pdf")) }) }
                            }
                            LibraryEmptyRenderState.NONE -> null
                        },
                    footerContent = {
                        DownloadableBooksSection(books = uiState.downloadableBooks, downloadStateMap = uiState.downloadState, isLoading = uiState.isDownloadableLoading, isDriveAuthorized = driveAuthHelper == null || driveAuthHelper.isAuthorized(), onConnectDrive = { row ->
                            pendingDownloadId = row.id
                            showDriveConnectDialog = true
                        }, onConfirmDownload = onDownload)
                    },
                )
            }
        }
        RemoveBookDialog(bookToDelete = uiState.bookToDelete, onDismiss = onDismissDelete, onConfirmLocalOnly = onConfirmLocalOnly, onConfirmLocalAndDrive = onConfirmLocalAndDrive)
        val activeDownload = uiState.downloadState.entries.firstOrNull { (_, state) -> state is DownloadState.Downloading || state is DownloadState.Success }
        val activeDownloadBook = activeDownload?.let { (id, _) -> uiState.downloadableBooks.firstOrNull { it.id == id } }
        if (activeDownload != null) NexoDownloadOverlay(bookTitle = (activeDownload.value as? DownloadState.Success)?.title ?: activeDownloadBook?.title ?: "", coverUrl = activeDownloadBook?.coverUrl, isCompleted = activeDownload.value is DownloadState.Success, visible = true)
        LibrarySyncStatus(syncError = uiState.syncError, isSyncing = uiState.isSyncing)
        FilterSheetContent(showFilterSheet = uiState.showFilterSheet, filterFormat = uiState.filterFormat, onFormatSelected = onFormatSelected, onDismiss = onFilterToggle)
        if (showDriveConnectDialog) {
            NexoDialog(title = stringResource(R.string.drive_connect_prompt_title), body = stringResource(R.string.drive_connect_prompt_body), confirmText = stringResource(R.string.drive_connect_prompt_accept), dismissText = stringResource(R.string.drive_connect_prompt_decline), icon = NexoIcons.CloudDownload, onConfirm = {
                showDriveConnectDialog = false
                val helper = driveAuthHelper
                val clientId = BuildConfig.GOOGLE_OAUTH_ANDROID_CLIENT_ID
                if (helper == null || clientId.isBlank()) {
                    Toast.makeText(context, driveConfigErrorText, Toast.LENGTH_SHORT).show()
                    pendingDownloadId = null
                    return@NexoDialog
                }
                isAuthorizingDrive = true
                driveAuthLauncher.launch(helper.beginAuth())
            }, onDismiss = {
                showDriveConnectDialog = false
                pendingDownloadId = null
            })
        }
    }
}
