package com.nextpage.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nextpage.BuildConfig
import com.nextpage.data.remote.drive.GoogleDriveAuthHelper
import com.nextpage.data.session.ReadingGoalPreferences
import com.nextpage.domain.model.AuthSession
import com.nextpage.domain.model.HighlightColor
import com.nextpage.domain.model.ThemeMode
import com.nextpage.domain.repository.CacheRepository
import com.nextpage.domain.repository.DictionaryRepository
import com.nextpage.domain.repository.LibraryRepository
import com.nextpage.domain.repository.StorageRepository
import com.nextpage.presentation.navigation.NextPageDestination
import com.nextpage.presentation.navigation.rememberDictionaryViewModel
import com.nextpage.presentation.navigation.rememberPerformanceViewModel
import com.nextpage.presentation.navigation.rememberSettingsDevicesViewModel
import com.nextpage.presentation.navigation.rememberStorageViewModel
import com.nextpage.presentation.screen.settings.AboutScreen
import com.nextpage.presentation.screen.settings.PerformanceScreen
import com.nextpage.presentation.screen.settings.SettingsAccountScreen
import com.nextpage.presentation.screen.settings.SettingsDataStorageScreen
import com.nextpage.presentation.screen.settings.SettingsDevicesScreen
import com.nextpage.presentation.screen.settings.SettingsLanguageScreen
import com.nextpage.presentation.screen.settings.SettingsListScreen
import com.nextpage.presentation.screen.settings.SettingsNotificationsScreen
import com.nextpage.presentation.screen.settings.SettingsPaletteScreen
import com.nextpage.presentation.screen.settings.SettingsStatisticsScreen
import com.nextpage.presentation.screen.settings.SettingsThemeScreen
import com.nextpage.presentation.screen.settings.StorageScreen
import com.nextpage.presentation.screen.settings.SyncScreen
import com.nextpage.presentation.theme.NextPageTheme
import com.nextpage.presentation.viewmodel.StatisticsViewModel
import com.nextpage.presentation.viewmodel.UpdateViewModel

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    authSession: AuthSession?,
    appThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialRoute: String? = null,
    onInitialRouteConsumed: () -> Unit = {},
    onAppThemeModeChanged: (ThemeMode) -> Unit = {},
    onLogout: () -> Unit = {},
    customHighlightColors: List<String>? = null,
    onUpdateCustomHighlightColor: (Int, String) -> Unit = { _, _ -> },
    onAddCustomHighlightColor: () -> Unit = {},
    onDeleteCustomHighlightColor: (Int) -> Unit = {},
    onResetCustomHighlightColors: () -> Unit = {},
    onNavigateToLogViewer: () -> Unit = {},
    statisticsViewModel: StatisticsViewModel,
    dictionaryRepository: DictionaryRepository? = null,
    driveAuthHelper: GoogleDriveAuthHelper? = null,
    readingGoalPreferences: ReadingGoalPreferences? = null,
    storageRepository: StorageRepository? = null,
    cacheRepository: CacheRepository? = null,
    libraryRepository: LibraryRepository? = null,
    updateViewModel: UpdateViewModel? = null,
) {
    SettingsScreenContent(
        contentPadding = contentPadding,
        authSession = authSession,
        appThemeMode = appThemeMode,
        initialRoute = initialRoute,
        onInitialRouteConsumed = onInitialRouteConsumed,
        onAppThemeModeChanged = onAppThemeModeChanged,
        onLogout = onLogout,
        customHighlightColors = customHighlightColors,
        onUpdateCustomHighlightColor = onUpdateCustomHighlightColor,
        onAddCustomHighlightColor = onAddCustomHighlightColor,
        onDeleteCustomHighlightColor = onDeleteCustomHighlightColor,
        onResetCustomHighlightColors = onResetCustomHighlightColors,
        onNavigateToLogViewer = onNavigateToLogViewer,
        statisticsViewModel = statisticsViewModel,
        dictionaryRepository = dictionaryRepository,
        driveAuthHelper = driveAuthHelper,
        readingGoalPreferences = readingGoalPreferences,
        storageRepository = storageRepository,
        cacheRepository = cacheRepository,
        libraryRepository = libraryRepository,
        updateViewModel = updateViewModel,
    )
}

@Composable
private fun SettingsScreenContent(
    contentPadding: PaddingValues,
    authSession: AuthSession?,
    appThemeMode: ThemeMode = ThemeMode.SYSTEM,
    initialRoute: String? = null,
    onInitialRouteConsumed: () -> Unit = {},
    onAppThemeModeChanged: (ThemeMode) -> Unit = {},
    onLogout: () -> Unit = {},
    customHighlightColors: List<String>? = null,
    onUpdateCustomHighlightColor: (Int, String) -> Unit = { _, _ -> },
    onAddCustomHighlightColor: () -> Unit = {},
    onDeleteCustomHighlightColor: (Int) -> Unit = {},
    onResetCustomHighlightColors: () -> Unit = {},
    onNavigateToLogViewer: () -> Unit = {},
    statisticsViewModel: StatisticsViewModel? = null,
    dictionaryRepository: DictionaryRepository? = null,
    driveAuthHelper: GoogleDriveAuthHelper? = null,
    readingGoalPreferences: ReadingGoalPreferences? = null,
    storageRepository: StorageRepository? = null,
    cacheRepository: CacheRepository? = null,
    libraryRepository: LibraryRepository? = null,
    updateViewModel: UpdateViewModel? = null,
) {
    val nestedNavController = rememberNavController()
    val dictionaryViewModel = rememberDictionaryViewModel(dictionaryRepository)
    val storageViewModel = rememberStorageViewModel(storageRepository, cacheRepository, libraryRepository)

    val start = initialRoute ?: NextPageDestination.SettingsList.route

    // One-shot: consume the deep-link after the NavHost first composes so a
    // later bottom-nav Settings tap uses the default start route instead.
    LaunchedEffect(Unit) {
        onInitialRouteConsumed()
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        NavHost(
            navController = nestedNavController,
            startDestination = start,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(route = NextPageDestination.SettingsList.route) {
                SettingsListScreen(
                    authSession = authSession,
                    appThemeMode = appThemeMode,
                    readingGoalPreferences = readingGoalPreferences,
                    onNavigateToAccount = {
                        nestedNavController.navigate(NextPageDestination.SettingsAccount.route)
                    },
                    onNavigateToTheme = {
                        nestedNavController.navigate(NextPageDestination.SettingsTheme.route)
                    },
                    onNavigateToLanguage = {
                        nestedNavController.navigate(NextPageDestination.SettingsLanguage.route)
                    },
                    onNavigateToPalette = {
                        nestedNavController.navigate(NextPageDestination.SettingsPalette.route)
                    },
                    onNavigateToDataStorage = {
                        nestedNavController.navigate(NextPageDestination.SettingsDataStorage.route)
                    },
                    onNavigateToNotifications = {
                        nestedNavController.navigate(NextPageDestination.SettingsNotifications.route)
                    },
                    onNavigateToAbout = {
                        nestedNavController.navigate(NextPageDestination.SettingsAbout.route)
                    },
                    onNavigateToDictionary = {
                        nestedNavController.navigate(NextPageDestination.SettingsDictionary.route)
                    },
                    onNavigateToDevices = {
                        nestedNavController.navigate(NextPageDestination.SettingsDevices.route)
                    },
                    onNavigateToDailyGoal = {
                        nestedNavController.navigate(NextPageDestination.SettingsDailyGoal.route)
                    },
                    onNavigateToPerformance = {
                        nestedNavController.navigate(NextPageDestination.SettingsPerformance.route)
                    },
                    onNavigateToLogViewer = onNavigateToLogViewer,
                    onNavigateToStorage = {
                        nestedNavController.navigate(NextPageDestination.SettingsStorage.route)
                    },
                    onNavigateToSync = {
                        nestedNavController.navigate(NextPageDestination.SettingsSync.route)
                    },
                )
            }

            composable(route = NextPageDestination.SettingsAccount.route) {
                SettingsAccountScreen(
                    authSession = authSession,
                    onLogout = onLogout,
                    // When Account is the start destination, popBackStack returns
                    // false (no List in the stack) — fall back to the Settings list
                    // so the back arrow never gets stuck.
                    onBack = {
                        if (!nestedNavController.popBackStack()) {
                            nestedNavController.navigate(NextPageDestination.SettingsList.route)
                        }
                    },
                )
            }

            composable(route = NextPageDestination.SettingsTheme.route) {
                SettingsThemeScreen(
                    appThemeMode = appThemeMode,
                    onAppThemeModeChanged = onAppThemeModeChanged,
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NextPageDestination.SettingsLanguage.route) {
                SettingsLanguageScreen(
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NextPageDestination.SettingsPalette.route) {
                SettingsPaletteScreen(
                    customHighlightColors = customHighlightColors,
                    onUpdateCustomHighlightColor = onUpdateCustomHighlightColor,
                    onAddCustomHighlightColor = onAddCustomHighlightColor,
                    onDeleteCustomHighlightColor = onDeleteCustomHighlightColor,
                    onResetCustomHighlightColors = onResetCustomHighlightColors,
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NextPageDestination.SettingsDataStorage.route) {
                SettingsDataStorageScreen(
                    driveAuthHelper = driveAuthHelper ?: return@composable,
                    onNavigateToStatistics = {
                        nestedNavController.navigate(NextPageDestination.SettingsStatistics.route)
                    },
                    onBack = { nestedNavController.popBackStack() },
                    onNavigateToStorage = {
                        nestedNavController.navigate(NextPageDestination.SettingsStorage.route)
                    },
                )
            }

            composable(route = NextPageDestination.SettingsNotifications.route) {
                SettingsNotificationsScreen(
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NextPageDestination.SettingsStorage.route) {
                val vm = storageViewModel
                if (vm != null) {
                    val storageState by vm.uiState.collectAsStateWithLifecycle()
                    StorageScreen(
                        uiState = storageState,
                        onBack = { nestedNavController.popBackStack() },
                        onClearCache = vm::clearCache,
                        onRequestDeleteBook = vm::requestDeleteBook,
                        onDismissDelete = vm::dismissDeleteDialog,
                        onConfirmLocalOnly = vm::confirmDeleteLocalOnly,
                        onConfirmLocalAndDrive = vm::confirmDeleteLocalAndDrive,
                        onSweepOrphans = vm::sweepOrphans,
                    )
                }
            }

            composable(route = NextPageDestination.SettingsSync.route) {
                SyncScreen(
                    onBack = { nestedNavController.popBackStack() },
                    onViewLogs = {
                        onNavigateToLogViewer()
                    },
                )
            }

            composable(route = NextPageDestination.SettingsAbout.route) {
                val vm = updateViewModel
                if (vm != null) {
                    val updateState by vm.uiState.collectAsStateWithLifecycle()
                    AboutScreen(
                        onBack = { nestedNavController.popBackStack() },
                        isUpdateCheckEnabled = vm.isFeedEnabled(),
                        updateState = updateState,
                        onCheckUpdates = vm::checkManually,
                    )
                } else {
                    AboutScreen(
                        onBack = { nestedNavController.popBackStack() },
                    )
                }
            }

            composable(route = NextPageDestination.SettingsStatistics.route) {
                statisticsViewModel?.let { vm ->
                    SettingsStatisticsScreen(
                        viewModel = vm,
                        onBack = { nestedNavController.popBackStack() },
                    )
                }
            }

            composable(route = NextPageDestination.SettingsDictionary.route) {
                dictionaryViewModel?.let { vm ->
                    DictionaryScreen(
                        viewModel = vm,
                        onNavigateBack = { nestedNavController.popBackStack() },
                    )
                }
            }

            composable(route = NextPageDestination.SettingsDevices.route) {
                val viewModel = rememberSettingsDevicesViewModel(authSession?.userId)

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer =
                        LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_PAUSE) {
                                viewModel?.stopHeartbeat()
                            } else if (event == Lifecycle.Event.ON_RESUME) {
                                viewModel?.loadDevices()
                            }
                        }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // Carga inicial al montar la screen (el observer no se dispara retroactivamente)
                LaunchedEffect(viewModel) {
                    viewModel?.loadDevices()
                }

                if (viewModel != null) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    SettingsDevicesScreen(
                        uiState = uiState,
                        onRemove = { id -> viewModel.removeDevice(id) },
                        onBack = { nestedNavController.popBackStack() },
                    )
                }
            }

            composable(route = NextPageDestination.SettingsDailyGoal.route) {
                val prefs = readingGoalPreferences
                if (prefs != null) {
                    val current = prefs.load() ?: 30
                    // Reuse onboarding screen for editing; back button pops to Settings list.
                    OnboardingGoalScreen(
                        initialMinutes = current,
                        onSave = { minutes ->
                            prefs.save(minutes)
                            nestedNavController.popBackStack()
                        },
                        onNavigateBack = { nestedNavController.popBackStack() },
                    )
                }
            }

            // PP-1: destination registered only in debug builds.
            if (BuildConfig.DEBUG) {
                composable(route = NextPageDestination.SettingsPerformance.route) {
                    PerformanceScreen(
                        onBack = { nestedNavController.popBackStack() },
                        viewModel = rememberPerformanceViewModel(),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() {
    NextPageTheme(darkTheme = true) {
        SettingsScreenContent(
            contentPadding = PaddingValues(16.dp),
            authSession =
                AuthSession(
                    userId = "local-1",
                    email = "reader@nextpage.app",
                    displayName = "Reader",
                    provider = "email",
                ),
            appThemeMode = ThemeMode.SYSTEM,
            customHighlightColors = HighlightColor.defaultHexList(),
            onAppThemeModeChanged = {},
            onLogout = {},
            onUpdateCustomHighlightColor = { _, _ -> },
            onAddCustomHighlightColor = {},
            onDeleteCustomHighlightColor = {},
            onResetCustomHighlightColors = {},
            onNavigateToLogViewer = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenLightPreview() {
    NextPageTheme(darkTheme = false) {
        SettingsScreenContent(
            contentPadding = PaddingValues(16.dp),
            authSession =
                AuthSession(
                    userId = "local-1",
                    email = "reader@nextpage.app",
                    displayName = "Reader",
                    provider = "email",
                ),
            appThemeMode = ThemeMode.SYSTEM,
            customHighlightColors = HighlightColor.defaultHexList(),
            onAppThemeModeChanged = {},
            onLogout = {},
            onUpdateCustomHighlightColor = { _, _ -> },
            onAddCustomHighlightColor = {},
            onDeleteCustomHighlightColor = {},
            onResetCustomHighlightColors = {},
            onNavigateToLogViewer = {},
        )
    }
}
