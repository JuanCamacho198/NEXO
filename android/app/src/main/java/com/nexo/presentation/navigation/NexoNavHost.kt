package com.nexo.presentation.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nexo.data.session.DriveConnectPromptPrefs
import com.nexo.debug.DebugPrefs
import com.nexo.di.AppContainer
import com.nexo.domain.model.AuthSession
import com.nexo.presentation.debug.DebugPanel
import com.nexo.presentation.feature.highlights.HighlightsScreen
import com.nexo.presentation.navigation.feature.authGraph
import com.nexo.presentation.navigation.feature.bookDetailGraph
import com.nexo.presentation.navigation.feature.discoverGraph
import com.nexo.presentation.navigation.feature.homeGraph
import com.nexo.presentation.navigation.feature.libraryGraph
import com.nexo.presentation.navigation.feature.onboardingGraph
import com.nexo.presentation.navigation.feature.readerGraph
import com.nexo.presentation.navigation.feature.settingsGraph
import com.nexo.presentation.screen.StatisticsScreen
import com.nexo.presentation.screen.settings.UpdateDialogHost
import com.nexo.ui.components.atoms.NexoSnackbar
import com.nexo.ui.components.molecules.BottomNavItem
import com.nexo.ui.components.molecules.NexoBottomNavBar
import com.nexo.ui.icons.NexoIcons

/**
 * Max time an import may stay non-Idle before the overlay watchdog force-
 * resets it. Generous enough for legitimate large-book imports (the timer
 * restarts on every stage change), yet bounded so a genuinely stuck overlay
 * never blocks the UI forever.
 */
private const val IMPORT_OVERLAY_WATCHDOG_TIMEOUT_MS = 120_000L

@Composable
fun NexoNavHost(
    appContainer: AppContainer,
    appThemeMode: com.nexo.domain.model.ThemeMode = com.nexo.domain.model.ThemeMode.SYSTEM,
    onAppThemeModeChanged: (com.nexo.domain.model.ThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val driveConnectPromptPrefs = remember { DriveConnectPromptPrefs(context) }
    val driveAuthHelper = appContainer.googleDriveAuthHelper

    var selectedBookId by rememberSaveable { mutableStateOf("") }
    var selectedBookFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedBookFormat by rememberSaveable { mutableStateOf("epub") }

    // One-shot deep-link target for the nested Settings NavHost (e.g. account).
    // Consumed on first composition so a later bottom-nav Settings tap doesn't
    // re-open the stale route.
    var settingsInitialRoute by rememberSaveable { mutableStateOf<String?>(null) }

    val navHostViewModels = rememberNavHostViewModels(appContainer, selectedBookId)
    val libraryViewModel = navHostViewModels.library
    val readerViewModel = navHostViewModels.reader
    val highlightsViewModel = navHostViewModels.highlights
    val statisticsViewModel = navHostViewModels.statistics
    val authViewModel = navHostViewModels.auth
    val homeViewModel = navHostViewModels.home
    val debugViewModel = navHostViewModels.debug
    val discoverViewModel = navHostViewModels.discover

    val authState by authViewModel.uiState.collectAsStateWithLifecycle()
    val isAuthenticated = authState.currentSession != null
    val isCheckingSession = authState.isCheckingSession

    SessionSyncEffect(
        session = authState.currentSession,
        homeViewModel = homeViewModel,
        getStatisticsUseCase = appContainer.getStatisticsUseCase,
        readerViewModel = readerViewModel,
    )

    // Resolved here (not inside the screen) so the Discover surface no longer
    // reads the session through a service locator. Null when signed out.
    val discoverUserInitial =
        remember(authState.currentSession) {
            discoverUserInitialOf(authState.currentSession)
        }

    var showDebugSheet by remember { mutableStateOf(false) }

    GlobalEventCollector(
        libraryUiEvent = libraryViewModel.uiEvent,
        readerUiEvent = readerViewModel.uiEvent,
        highlightsUiEvent = highlightsViewModel.uiEvent,
        statisticsUiEvent = statisticsViewModel.uiEvent,
        homeUiEvent = homeViewModel.uiEvent,
        authUiEvent = authViewModel.uiEvent,
        navController = navController,
        snackbarHostState = snackbarHostState,
        context = context,
        onOpenBookAtLocation = { event ->
            val book = appContainer.libraryRepository.getBookById(event.bookId)
            if (book != null) {
                selectedBookId = book.id
                selectedBookFilePath = book.filePath
                selectedBookFormat = book.format
                readerViewModel.lifecycleHolder.navigateToCfiAfterLoad(event.cfiRange)
                navController.navigate(ReaderRoute(book.id, book.filePath, book.format)) {
                    launchSingleTop = true
                }
            } else {
                snackbarHostState.showSnackbar(context.getString(com.nexo.R.string.book_not_found))
            }
        },
    )

    DrivePromptHost(
        driveAuthHelper = driveAuthHelper,
        prefs = driveConnectPromptPrefs,
        authSession = authState.currentSession,
        importEvents = libraryViewModel.importEvents,
        snackbarHostState = snackbarHostState,
        syncService = appContainer.syncService,
    )

    // ── Addon install deep-link dialog (nextpage://install) ─────────────
    AddonInstallDialogHost(controller = appContainer.installDeepLinkController)

    // ── App-update self-check (SDD app-auto-update) ────────────────────
    // Single shared instance: the startup check fires once here, the About
    // manual row (settings graph) drives the same state. The dialog host
    // lives at root so the startup prompt surfaces on any screen.
    val updateViewModel = rememberUpdateViewModel(appContainer)
    LaunchedEffect(Unit) {
        updateViewModel.checkAtStartup()
    }
    UpdateDialogHost(viewModel = updateViewModel)

    // ── Supabase OAuth deep-link handling ────────────────────────────
    // NOTE: Google sign-in now uses native Credential Manager (no browser OAuth).
    // This deep-link handler is kept for backward compatibility with any
    // future OAuth flows that may use browser-based auth, but is currently a no-op
    // for Google sign-in.

    // NOTE: Book loading is handled directly by ReaderScreen via LaunchedEffect(selectedBookId, bookFilePath, bookFormat).
    // No need to pre-load here; restoreProgressForBook is called inside loadBook flow.

    val bottomNavDestinations =
        listOf(
            NexoDestination.Home,
            NexoDestination.Library,
            NexoDestination.Discover,
            NexoDestination.Highlights,
            NexoDestination.Settings,
        )

    // Whitelist de rutas donde el BottomNav debe mostrarse
    val bottomNavRoutes = bottomNavDestinations.map { it.route }.toSet()

    // Onboarding goal gating (REQ-daily-reading-goal-2, SCEN-daily-reading-goal-1/2):
    // authenticated users with no stored goal land on onboarding/goal first.
    // Reactive: bump dailyGoalVersion after save so hasDailyGoal recomputes without needing process restart.
    var dailyGoalVersion by remember { mutableStateOf(0) }
    val hasDailyGoal = remember(dailyGoalVersion) { appContainer.readingGoalPreferences.load() != null }

    val startDestination =
        when {
            !isAuthenticated -> NexoDestination.Auth.route
            !hasDailyGoal -> NexoDestination.OnboardingGoal.route
            else -> NexoDestination.Home.route
        }

    // ── Password-reset deep link (nextpage://auth/reset-password) ──────
    // Cold-start resolution: if the app was opened from a reset-password
    DeepLinkHandler(
        navController = navController,
        isCheckingSession = isCheckingSession,
        isAuthenticated = isAuthenticated,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (isCheckingSession) {
            // Fallback while session is being restored. The native splash
            // screen (MainActivity) normally covers this window via
            // setKeepOnScreenCondition; this centered spinner guards against
            // any gap after the splash dismisses before navigation settles.
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Scaffold(
                snackbarHost = {
                    SnackbarHost(hostState = snackbarHostState) { data ->
                        NexoSnackbar(snackbarData = data)
                    }
                },
                bottomBar = {
                    if (isAuthenticated) {
                        val currentBackStack = navController.currentBackStackEntryAsState().value
                        val currentRoute = currentBackStack?.destination?.route
                        if (currentRoute != null && currentRoute in bottomNavRoutes) {
                            val bottomNavItems =
                                bottomNavDestinations.map { dest ->
                                    BottomNavItem(dest.route, dest.labelRes, checkNotNull(dest.icon))
                                }
                            NexoBottomNavBar(
                                destinations = bottomNavItems,
                                currentRoute = currentRoute,
                                onTabSelected = { route ->
                                    navController.navigateToBottomTab(
                                        route = route,
                                        homeRoute = NexoDestination.Home.route,
                                    )
                                },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                    ) {
                        authGraph(
                            navController = navController,
                            authViewModel = authViewModel,
                            appContainer = appContainer,
                        )

                        onboardingGraph(
                            navController = navController,
                            appContainer = appContainer,
                            onGoalSaved = { dailyGoalVersion++ },
                        )

                        homeGraph(
                            navController = navController,
                            homeViewModel = homeViewModel,
                            libraryViewModel = libraryViewModel,
                            contentPadding = innerPadding,
                            onSelectBook = { id, path, format ->
                                selectedBookId = id
                                selectedBookFilePath = path
                                selectedBookFormat = format
                            },
                            onSettingsInitialRoute = { route -> settingsInitialRoute = route },
                        )

                        bookDetailGraph(
                            navController = navController,
                            appContainer = appContainer,
                            readerViewModel = readerViewModel,
                            contentPadding = innerPadding,
                            onSelectBook = { id, path, format ->
                                selectedBookId = id
                                selectedBookFilePath = path
                                selectedBookFormat = format
                            },
                        )

                        discoverGraph(
                            navController = navController,
                            contentPadding = innerPadding,
                            discoverViewModel = discoverViewModel,
                            catalogProvider = appContainer.catalogProvider,
                            discoverUserInitial = discoverUserInitial,
                            downloadAndImportBookUseCase = appContainer.downloadAndImportBookUseCase,
                        )

                        libraryGraph(
                            navController = navController,
                            libraryViewModel = libraryViewModel,
                            driveAuthHelper = driveAuthHelper,
                            currentSession = authState.currentSession,
                            contentPadding = innerPadding,
                            onSelectBook = { id, path, format ->
                                selectedBookId = id
                                selectedBookFilePath = path
                                selectedBookFormat = format
                            },
                            onSettingsInitialRoute = { route -> settingsInitialRoute = route },
                        )

                        readerGraph(
                            navController = navController,
                            readerViewModel = readerViewModel,
                            selectedBookId = selectedBookId,
                            selectedBookFilePath = selectedBookFilePath,
                            selectedBookFormat = selectedBookFormat,
                            contentPadding = innerPadding,
                        )

                        composable(
                            route = NexoDestination.Highlights.route,
                            enterTransition = { fadeIn() },
                            exitTransition = { fadeOut() },
                            popEnterTransition = { fadeIn() },
                            popExitTransition = { fadeOut() },
                        ) {
                            HighlightsScreen(
                                contentPadding = innerPadding,
                                viewModel = highlightsViewModel,
                                authSession = authState.currentSession,
                                onOpenAccount = {
                                    settingsInitialRoute = NexoDestination.SettingsAccount.route
                                    navController.navigate(NexoDestination.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }

                        composable(
                            route = NexoDestination.Statistics.route,
                            enterTransition = { fadeIn() },
                            exitTransition = { fadeOut() },
                            popEnterTransition = { fadeIn() },
                            popExitTransition = { fadeOut() },
                        ) {
                            StatisticsScreen(
                                contentPadding = innerPadding,
                                viewModel = statisticsViewModel,
                                authSession = authState.currentSession,
                                onOpenAccount = {
                                    settingsInitialRoute = NexoDestination.SettingsAccount.route
                                    navController.navigate(NexoDestination.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }

                        settingsGraph(
                            navController = navController,
                            appContainer = appContainer,
                            authViewModel = authViewModel,
                            statisticsViewModel = statisticsViewModel,
                            dictionaryRepository = appContainer.dictionaryRepository,
                            driveAuthHelper = driveAuthHelper,
                            currentSession = authState.currentSession,
                            settingsInitialRoute = settingsInitialRoute,
                            onSettingsInitialRouteConsumed = { settingsInitialRoute = null },
                            appThemeMode = appThemeMode,
                            onAppThemeModeChanged = onAppThemeModeChanged,
                            contentPadding = innerPadding,
                            updateViewModel = updateViewModel,
                        )
                    }

                    ImportOverlayHost(libraryViewModel = libraryViewModel)
                }

                // DrivePromptHost handles its own dialog; no host-level dialog needed

                // ── Debug FAB ──────────────────────────────────────────────────
                // FR-AD1: the persisted DebugPrefs toggle is the SOLE authority.
                // The previous local-user-only conjunct was removed.
                val showDebugFab = DebugPrefs.isEnabled(context)

                if (showDebugFab) {
                    FloatingActionButton(
                        onClick = { showDebugSheet = true },
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Icon(
                            imageVector = NexoIcons.BugReport,
                            contentDescription = "Debug",
                        )
                    }
                }

                // ── Debug Panel Sheet ──────────────────────────────────────────
                if (showDebugSheet) {
                    // SDD reader-uiState-cleanup S6: panel takes the session slice, not the VM.
                    val readerSessionUiState by readerViewModel.sessionUiState.collectAsStateWithLifecycle()
                    DebugPanel(
                        viewModel = debugViewModel,
                        authViewModel = authViewModel,
                        session = readerSessionUiState,
                        syncService = appContainer.syncService,
                        onDismiss = { showDebugSheet = false },
                    )
                }
            }
        }
    }
}

/**
 * First glyph of the signed-in user's display name, falling back to the email.
 * Null when signed out (or when neither field carries a character), which makes
 * the Discover header render its neutral avatar placeholder.
 */
private fun discoverUserInitialOf(session: AuthSession?): String? {
    val displayName = session?.displayName?.trim().orEmpty()
    val source = if (displayName.isNotEmpty()) displayName else session?.email?.trim().orEmpty()
    return source.firstOrNull()?.uppercaseChar()?.toString()
}
