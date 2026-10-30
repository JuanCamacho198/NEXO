package com.nexo.presentation.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexo.R
import com.nexo.domain.model.Book
import com.nexo.presentation.screen.HomeTags
import com.nexo.presentation.theme.NexoDimens
import com.nexo.presentation.theme.NexoTheme
import com.nexo.presentation.viewmodel.HomeUiState
import com.nexo.presentation.viewmodel.HomeViewModel
import com.nexo.ui.components.atoms.NexoEmptyState
import com.nexo.ui.components.molecules.ContinueReadingSkeleton
import com.nexo.ui.components.molecules.NotificationSheet
import com.nexo.ui.icons.NexoIcons

/**
 * Render mode for a Home section that owns an empty state.
 *
 * Pure and JVM-testable so the loading gate (LS1-LS3) is verifiable without a
 * device: a section shows its skeleton until the first content resolves, its
 * empty state only after loading finished, and its content otherwise.
 */
internal enum class HomeSectionRenderState { SKELETON, EMPTY, CONTENT }

/**
 * Standard horizontal inset for Home sections.
 *
 * The [LazyColumn] no longer carries this padding itself, because the
 * first-session hero has to reach both screen edges; each padded section
 * applies it. Matches the app's 20px-plus-card screen padding convention in
 * [NexoDimens] terms.
 */
private val ScreenHorizontalInset = 24.dp

/**
 * @return [HomeSectionRenderState.SKELETON] while [isLoading] and the section
 *   has not emitted any item yet, [HomeSectionRenderState.EMPTY] when loading
 *   finished with no items, otherwise [HomeSectionRenderState.CONTENT].
 */
internal fun homeSectionRenderState(
    isLoading: Boolean,
    isEmpty: Boolean,
): HomeSectionRenderState =
    when {
        isLoading && isEmpty -> HomeSectionRenderState.SKELETON
        isEmpty -> HomeSectionRenderState.EMPTY
        else -> HomeSectionRenderState.CONTENT
    }

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    viewModel: HomeViewModel,
    onNavigateToLibrary: () -> Unit,
    onNavigateToHighlights: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onNavigateToStatistics: () -> Unit,
    onNavigateToDiscover: () -> Unit = {},
    onNavigateToDriveSetup: () -> Unit = {},
    onBookSelected: (String, String, String) -> Unit,
    onContinueReading: (String, String?, String) -> Unit,
    onImportBook: () -> Unit,
    notificationGroups: List<com.nexo.domain.notification.NotificationGroup> = emptyList(),
    unreadNotificationCount: Int = 0,
    onOpenNotification: (com.nexo.domain.notification.Notification) -> Unit = {},
    onNotificationsOpened: () -> Unit = {},
    onEditBook: (Book) -> Unit = {},
    onMarkCompleted: (Book) -> Unit = {},
    onMarkPlanToRead: (Book) -> Unit = {},
    onShareBook: (Book) -> Unit = {},
    onRequestDeleteBook: (Book) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreenContent(uiState = uiState, contentPadding = contentPadding, onNavigateToLibrary = onNavigateToLibrary, onNavigateToHighlights = onNavigateToHighlights, onNavigateToSettings = onNavigateToSettings, onOpenAccount = onOpenAccount, onNavigateToStatistics = onNavigateToStatistics, onNavigateToDiscover = onNavigateToDiscover, onNavigateToDriveSetup = onNavigateToDriveSetup, onBookSelected = onBookSelected, onContinueReading = onContinueReading, onImportBook = onImportBook, onSearchQueryChange = viewModel::onSearchQueryChanged, onToggleSearch = viewModel::onToggleSearch, notificationGroups = notificationGroups, unreadNotificationCount = unreadNotificationCount, onOpenNotification = onOpenNotification, onNotificationsOpened = onNotificationsOpened, onEditBook = onEditBook, onMarkCompleted = onMarkCompleted, onMarkPlanToRead = onMarkPlanToRead, onShareBook = onShareBook, onRequestDeleteBook = onRequestDeleteBook)
}

@Composable
fun HomeScreenContent(
    uiState: HomeUiState,
    contentPadding: PaddingValues,
    onNavigateToLibrary: () -> Unit,
    onNavigateToHighlights: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onNavigateToStatistics: () -> Unit,
    onNavigateToDiscover: () -> Unit = {},
    onNavigateToDriveSetup: () -> Unit = {},
    onBookSelected: (String, String, String) -> Unit,
    onContinueReading: (String, String?, String) -> Unit,
    onImportBook: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    notificationGroups: List<com.nexo.domain.notification.NotificationGroup> = emptyList(),
    unreadNotificationCount: Int = 0,
    onOpenNotification: (com.nexo.domain.notification.Notification) -> Unit = {},
    onNotificationsOpened: () -> Unit = {},
    onEditBook: (Book) -> Unit = {},
    onMarkCompleted: (Book) -> Unit = {},
    onMarkPlanToRead: (Book) -> Unit = {},
    onShareBook: (Book) -> Unit = {},
    onRequestDeleteBook: (Book) -> Unit = {},
) {
    var showNotifications by remember { mutableStateOf(false) }
    if (showNotifications) {
        NotificationSheet(
            groups = notificationGroups,
            onOpen = onOpenNotification,
            onDismiss = { showNotifications = false },
        )
    }
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .testTag(HomeTags.SCREEN_ROOT),
        verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(NexoDimens.spacingMd),
    ) {
        if (uiState.showSearch) {
            item { Box(modifier = Modifier.padding(horizontal = ScreenHorizontalInset)) { SearchBarSection(searchQuery = uiState.searchQuery, onSearchQueryChange = onSearchQueryChange, onCloseSearch = onToggleSearch) } }
            if (uiState.searchResults.isNotEmpty()) {
                item { Box(modifier = Modifier.padding(horizontal = ScreenHorizontalInset)) { SearchResultsList(results = uiState.searchResults, onBookSelected = onBookSelected) } }
            } else if (uiState.searchQuery.isNotBlank()) {
                item { Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { NexoEmptyState(icon = NexoIcons.Search, title = stringResource(R.string.library_search_no_results), subtitle = stringResource(R.string.library_search_empty_subtitle)) } }
            }
        }
        if (!uiState.showSearch) {
            if (uiState.isLibraryEmpty) {
                // First-session Home: full-bleed hero, then the genre row and the
                // cloud-sync banner. No "continue reading" carousel and no shelf —
                // there is nothing in the library to continue or to shelve.
                item {
                    HomeHeroSection(onExplore = onNavigateToDiscover, avatarUrl = uiState.avatarUrl, userName = uiState.userName, streakDays = uiState.currentStreak, onAvatarClick = onOpenAccount, onStreakClick = onNavigateToStatistics, onNotificationsClick = {
                        showNotifications = true
                        onNotificationsOpened()
                    })
                }
                item { HomeGenresSection(modifier = Modifier.padding(horizontal = ScreenHorizontalInset), onBrowseAll = onNavigateToDiscover) }
                item { HomeSyncBanner(onClick = onNavigateToDriveSetup, modifier = Modifier.padding(horizontal = ScreenHorizontalInset)) }
            } else {
                item {
                    CollapsedHomeHeroSection(
                        onExplore = onNavigateToDiscover,
                        avatarUrl = uiState.avatarUrl,
                        userName = uiState.userName,
                        streakDays = uiState.currentStreak,
                        onAvatarClick = onOpenAccount,
                        onStreakClick = onNavigateToStatistics,
                        onNotificationsClick = {
                            showNotifications = true
                            onNotificationsOpened()
                        },
                    )
                }
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalInset)) {
                        if (homeSectionRenderState(uiState.isLoading, uiState.currentBooks.isEmpty()) == HomeSectionRenderState.SKELETON) {
                            ContinueReadingSkeleton()
                        } else {
                            ContinueReadingSection(books = uiState.currentBooks, progressPercentByBook = uiState.progressPercentByBook, onViewAll = onNavigateToLibrary, onBookSelected = onBookSelected, onContinueReading = onContinueReading, onEdit = onEditBook, onMarkCompleted = onMarkCompleted, onMarkPlanToRead = onMarkPlanToRead, onShare = onShareBook, onDelete = onRequestDeleteBook)
                        }
                    }
                }
                item {
                    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalInset)) {
                        if (homeSectionRenderState(uiState.isLoading, uiState.recentBooks.isEmpty()) == HomeSectionRenderState.SKELETON) {
                            // My Bookshelf is a horizontal card row like the carousel, so it
                            // reuses the horizontal card skeleton with the shelf's card count.
                            ContinueReadingSkeleton(cards = 4)
                        } else {
                            MyBookshelfSection(books = uiState.recentBooks, onViewAll = onNavigateToLibrary, onBookSelected = onBookSelected)
                        }
                    }
                }
                item { HomeGenresSection(modifier = Modifier.padding(horizontal = ScreenHorizontalInset), onBrowseAll = onNavigateToDiscover) }
            }
        }
        item { Spacer(modifier = Modifier.height(NexoDimens.spacingMd)) }
        if (com.nexo.BuildConfig.DEBUG) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(R.string.home_version_format, com.nexo.BuildConfig.VERSION_NAME, com.nexo.BuildConfig.GIT_SHA, com.nexo.BuildConfig.BUILD_TIME), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            }
            item { Text(text = stringResource(R.string.debug_version_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) }
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeScreenEmptyDarkPreview() {
    NexoTheme(darkTheme = true) {
        HomeScreenContent(uiState = HomeUiState(userName = "María", currentStreak = 4, allBooks = emptyList(), isLoading = false, showSearch = false), contentPadding = PaddingValues(0.dp), onNavigateToLibrary = {}, onNavigateToHighlights = {}, onNavigateToSettings = {}, onNavigateToStatistics = {}, onBookSelected = { _, _, _ -> }, onContinueReading = { _, _, _ -> }, onImportBook = {}, onSearchQueryChange = {}, onToggleSearch = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenDarkPreview() {
    NexoTheme(darkTheme = true) {
        val sampleBook = Book(id = "book-1", title = "The Pragmatic Programmer", author = "David Thomas", coverPath = null, filePath = "/books/pragmatic.epub", format = "epub", updatedAtEpochMillis = 1L)
        HomeScreenContent(uiState = HomeUiState(userName = "María", minutesReadToday = 42, sessionsToday = 3, dailyProgressPercent = 0.5f, currentBooks = listOf(sampleBook), recentBooks = listOf(sampleBook), isLoading = false, showSearch = false, searchQuery = "", searchResults = emptyList()), contentPadding = PaddingValues(16.dp), onNavigateToLibrary = {}, onNavigateToHighlights = {}, onNavigateToSettings = {}, onNavigateToStatistics = {}, onBookSelected = { _, _, _ -> }, onContinueReading = { _, _, _ -> }, onImportBook = {}, onSearchQueryChange = {}, onToggleSearch = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenLightPreview() {
    NexoTheme(darkTheme = false) {
        val sampleBook = Book(id = "book-1", title = "The Pragmatic Programmer", author = "David Thomas", coverPath = null, filePath = "/books/pragmatic.epub", format = "epub", updatedAtEpochMillis = 1L)
        HomeScreenContent(uiState = HomeUiState(userName = "María", minutesReadToday = 42, sessionsToday = 3, dailyProgressPercent = 0.5f, currentBooks = listOf(sampleBook), recentBooks = listOf(sampleBook), isLoading = false, showSearch = false, searchQuery = "", searchResults = emptyList()), contentPadding = PaddingValues(16.dp), onNavigateToLibrary = {}, onNavigateToHighlights = {}, onNavigateToSettings = {}, onNavigateToStatistics = {}, onBookSelected = { _, _, _ -> }, onContinueReading = { _, _, _ -> }, onImportBook = {}, onSearchQueryChange = {}, onToggleSearch = {})
    }
}
