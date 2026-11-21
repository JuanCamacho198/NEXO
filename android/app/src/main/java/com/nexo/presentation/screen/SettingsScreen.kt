package com.nexo.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nexo.BuildConfig
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.data.session.NotificationPreferencesStore
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.HighlightColor
import com.nexo.domain.model.ThemeMode
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.StorageRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.navigation.rememberDictionaryViewModel
import com.nexo.presentation.navigation.rememberPerformanceViewModel
import com.nexo.presentation.navigation.rememberSettingsDevicesViewModel
import com.nexo.presentation.navigation.rememberStorageViewModel
import com.nexo.presentation.screen.settings.AboutContact
import com.nexo.presentation.screen.settings.AboutScreen
import com.nexo.presentation.screen.settings.LicensesScreen
import com.nexo.presentation.screen.settings.PerformanceScreen
import com.nexo.presentation.screen.settings.PrivacyPolicyScreen
import com.nexo.presentation.screen.settings.SettingsAccountScreen
import com.nexo.presentation.screen.settings.SettingsDataStorageScreen
import com.nexo.presentation.screen.settings.SettingsDefinitionScreen
import com.nexo.presentation.screen.settings.SettingsDevicesScreen
import com.nexo.presentation.screen.settings.SettingsLanguageScreen
import com.nexo.presentation.screen.settings.SettingsListScreen
import com.nexo.presentation.screen.settings.SettingsNotificationsScreen
import com.nexo.presentation.screen.settings.SettingsPaletteScreen
import com.nexo.presentation.screen.settings.SettingsStatisticsScreen
import com.nexo.presentation.screen.settings.SettingsThemeScreen
import com.nexo.presentation.screen.settings.SettingsTranslationScreen
import com.nexo.presentation.screen.settings.StorageScreen
import com.nexo.presentation.screen.settings.SyncScreen
import com.nexo.presentation.screen.settings.TermsScreen
import com.nexo.presentation.viewmodel.StatisticsViewModel
import com.nexo.presentation.viewmodel.UpdateViewModel
import com.nexo.ui.components.molecules.DEFINITION_LANGUAGE_OPTIONS
import com.nexo.ui.components.molecules.definitionLanguageName
import com.nexo.ui.theme.NexoTheme
import kotlinx.coroutines.launch

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
    readingGoalRepository: com.nexo.data.repository.ReadingGoalRepository? = null,
    notificationPreferencesStore: NotificationPreferencesStore? = null,
    storageRepository: StorageRepository? = null,
    cacheRepository: CacheRepository? = null,
    libraryRepository: LibraryRepository? = null,
    updateViewModel: UpdateViewModel? = null,
    onNavigateToAddons: () -> Unit = {},
    activeUser: ActiveUserProvider,
    translationPreferences: com.nexo.data.session.TranslationPreferences? = null,
    translationHistoryRepository: com.nexo.domain.repository.TranslationHistoryRepository? = null,
    definitionPreferences: com.nexo.data.session.DefinitionPreferences? = null,
    definitionHistoryRepository: com.nexo.domain.repository.DefinitionHistoryRepository? = null,
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
        readingGoalRepository = readingGoalRepository,
        notificationPreferencesStore = notificationPreferencesStore,
        storageRepository = storageRepository,
        cacheRepository = cacheRepository,
        libraryRepository = libraryRepository,
        updateViewModel = updateViewModel,
        onNavigateToAddons = onNavigateToAddons,
        activeUser = activeUser,
        translationPreferences = translationPreferences,
        translationHistoryRepository = translationHistoryRepository,
        definitionPreferences = definitionPreferences,
        definitionHistoryRepository = definitionHistoryRepository,
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
    readingGoalRepository: com.nexo.data.repository.ReadingGoalRepository? = null,
    notificationPreferencesStore: NotificationPreferencesStore? = null,
    storageRepository: StorageRepository? = null,
    cacheRepository: CacheRepository? = null,
    libraryRepository: LibraryRepository? = null,
    updateViewModel: UpdateViewModel? = null,
    onNavigateToAddons: () -> Unit = {},
    activeUser: ActiveUserProvider,
    translationPreferences: com.nexo.data.session.TranslationPreferences? = null,
    translationHistoryRepository: com.nexo.domain.repository.TranslationHistoryRepository? = null,
    definitionPreferences: com.nexo.data.session.DefinitionPreferences? = null,
    definitionHistoryRepository: com.nexo.domain.repository.DefinitionHistoryRepository? = null,
) {
    val nestedNavController = rememberNavController()
    // Contact opens outside the app, so the nested NavHost needs a context to
    // start the intent from.
    val context = LocalContext.current
    val dictionaryViewModel = rememberDictionaryViewModel(dictionaryRepository, activeUser)
    val storageViewModel = rememberStorageViewModel(storageRepository, cacheRepository, libraryRepository, activeUser)

    val start = initialRoute ?: NexoDestination.SettingsList.route

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
            composable(route = NexoDestination.SettingsList.route) {
                SettingsListScreen(
                    authSession = authSession,
                    appThemeMode = appThemeMode,
                    readingGoalPreferences = readingGoalPreferences,
                    onNavigateToAccount = {
                        nestedNavController.navigate(NexoDestination.SettingsAccount.route)
                    },
                    onNavigateToTheme = {
                        nestedNavController.navigate(NexoDestination.SettingsTheme.route)
                    },
                    onNavigateToLanguage = {
                        nestedNavController.navigate(NexoDestination.SettingsLanguage.route)
                    },
                    onNavigateToPalette = {
                        nestedNavController.navigate(NexoDestination.SettingsPalette.route)
                    },
                    onNavigateToDataStorage = {
                        nestedNavController.navigate(NexoDestination.SettingsDataStorage.route)
                    },
                    onNavigateToNotifications = {
                        nestedNavController.navigate(NexoDestination.SettingsNotifications.route)
                    },
                    onNavigateToAbout = {
                        nestedNavController.navigate(NexoDestination.SettingsAbout.route)
                    },
                    onNavigateToDictionary = {
                        nestedNavController.navigate(NexoDestination.SettingsDictionary.route)
                    },
                    onNavigateToDevices = {
                        nestedNavController.navigate(NexoDestination.SettingsDevices.route)
                    },
                    onNavigateToDailyGoal = {
                        nestedNavController.navigate(NexoDestination.SettingsDailyGoal.route)
                    },
                    onNavigateToTranslation = {
                        nestedNavController.navigate(NexoDestination.SettingsTranslation.route)
                    },
                    onNavigateToDefinition = {
                        nestedNavController.navigate(NexoDestination.SettingsDefinition.route)
                    },
                    onNavigateToPerformance = {
                        nestedNavController.navigate(NexoDestination.SettingsPerformance.route)
                    },
                    onNavigateToLogViewer = onNavigateToLogViewer,
                    onNavigateToStorage = {
                        nestedNavController.navigate(NexoDestination.SettingsStorage.route)
                    },
                    onNavigateToSync = {
                        nestedNavController.navigate(NexoDestination.SettingsSync.route)
                    },
                    onNavigateToAddons = onNavigateToAddons,
                )
            }

            composable(route = NexoDestination.SettingsAccount.route) {
                SettingsAccountScreen(
                    authSession = authSession,
                    onLogout = onLogout,
                    // When Account is the start destination, popBackStack returns
                    // false (no List in the stack) — fall back to the Settings list
                    // so the back arrow never gets stuck.
                    onBack = {
                        if (!nestedNavController.popBackStack()) {
                            nestedNavController.navigate(NexoDestination.SettingsList.route)
                        }
                    },
                )
            }

            composable(route = NexoDestination.SettingsTheme.route) {
                SettingsThemeScreen(
                    appThemeMode = appThemeMode,
                    onAppThemeModeChanged = onAppThemeModeChanged,
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NexoDestination.SettingsLanguage.route) {
                SettingsLanguageScreen(
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NexoDestination.SettingsTranslation.route) {
                SettingsTranslationScreen(
                    onBack = { nestedNavController.popBackStack() },
                    preferencesStore = translationPreferences,
                    historyRepository = translationHistoryRepository,
                )
            }

            composable(route = NexoDestination.SettingsDefinition.route) {
                SettingsDefinitionScreen(
                    onBack = { nestedNavController.popBackStack() },
                    preferencesStore = definitionPreferences,
                    historyRepository = definitionHistoryRepository,
                    // Resolved here because the display names are string resources,
                    // and the feature module owns none.
                    languageLabels =
                        DEFINITION_LANGUAGE_OPTIONS.associateWith { code ->
                            definitionLanguageName(code)
                        },
                )
            }

            composable(route = NexoDestination.SettingsPalette.route) {
                SettingsPaletteScreen(
                    customHighlightColors = customHighlightColors,
                    onUpdateCustomHighlightColor = onUpdateCustomHighlightColor,
                    onAddCustomHighlightColor = onAddCustomHighlightColor,
                    onDeleteCustomHighlightColor = onDeleteCustomHighlightColor,
                    onResetCustomHighlightColors = onResetCustomHighlightColors,
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NexoDestination.SettingsDataStorage.route) {
                SettingsDataStorageScreen(
                    driveAuthHelper = driveAuthHelper ?: return@composable,
                    onNavigateToStatistics = {
                        nestedNavController.navigate(NexoDestination.SettingsStatistics.route)
                    },
                    onBack = { nestedNavController.popBackStack() },
                    onNavigateToStorage = {
                        nestedNavController.navigate(NexoDestination.SettingsStorage.route)
                    },
                )
            }

            composable(route = NexoDestination.SettingsNotifications.route) {
                SettingsNotificationsScreen(
                    preferencesStore = notificationPreferencesStore,
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NexoDestination.SettingsStorage.route) {
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

            composable(route = NexoDestination.SettingsSync.route) {
                SyncScreen(
                    onBack = { nestedNavController.popBackStack() },
                    onViewLogs = {
                        onNavigateToLogViewer()
                    },
                )
            }

            // The nested NavHost owns privacy/terms so the About screen can push
            // them onto the stack it was itself pushed onto.
            composable(route = NexoDestination.SettingsPrivacy.route) {
                PrivacyPolicyScreen(
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NexoDestination.SettingsTerms.route) {
                TermsScreen(
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NexoDestination.SettingsLicenses.route) {
                LicensesScreen(
                    onBack = { nestedNavController.popBackStack() },
                )
            }

            composable(route = NexoDestination.SettingsAbout.route) {
                val vm = updateViewModel
                // Only rows with a destination get a handler. A null handler
                // renders an inert row with no chevron, so About never promises
                // a screen that does not exist: licences open the attribution
                // page from inside this nested stack, contact opens the
                // project's issue tracker, and a rating row has nowhere to go
                // until there is a store listing to rate.
                val onLicenses: () -> Unit = {
                    nestedNavController.navigate(NexoDestination.SettingsLicenses.route)
                }
                val onContact: (() -> Unit)? =
                    remember {
                        AboutContact.openSupportMailIntent(context)?.let { intent ->
                            { context.startActivity(intent) }
                        }
                    }
                if (vm != null) {
                    val updateState by vm.uiState.collectAsStateWithLifecycle()
                    AboutScreen(
                        onBack = { nestedNavController.popBackStack() },
                        onTerms = { nestedNavController.navigate(NexoDestination.SettingsTerms.route) },
                        onPrivacy = { nestedNavController.navigate(NexoDestination.SettingsPrivacy.route) },
                        onLicenses = onLicenses,
                        onContact = onContact,
                        isUpdateCheckEnabled = vm.isFeedEnabled(),
                        updateState = updateState,
                        onCheckUpdates = vm::checkManually,
                    )
                } else {
                    AboutScreen(
                        onBack = { nestedNavController.popBackStack() },
                        onTerms = { nestedNavController.navigate(NexoDestination.SettingsTerms.route) },
                        onPrivacy = { nestedNavController.navigate(NexoDestination.SettingsPrivacy.route) },
                        onLicenses = onLicenses,
                        onContact = onContact,
                    )
                }
            }

            composable(route = NexoDestination.SettingsStatistics.route) {
                statisticsViewModel?.let { vm ->
                    SettingsStatisticsScreen(
                        viewModel = vm,
                        onBack = { nestedNavController.popBackStack() },
                    )
                }
            }

            composable(route = NexoDestination.SettingsDictionary.route) {
                dictionaryViewModel?.let { vm ->
                    DictionaryScreen(
                        viewModel = vm,
                        onNavigateBack = { nestedNavController.popBackStack() },
                    )
                }
            }

            composable(route = NexoDestination.SettingsDevices.route) {
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

            composable(route = NexoDestination.SettingsDailyGoal.route) {
                val prefs = readingGoalPreferences
                if (prefs != null) {
                    val current = prefs.load() ?: 30
                    val saveScope = rememberCoroutineScope()
                    // Reuse onboarding screen for editing; back button pops to Settings list.
                    OnboardingGoalScreen(
                        initialMinutes = current,
                        onSave = { minutes ->
                            // Local first (instant UI consistency), then push the
                            // same value remote so an edit also travels across
                            // devices. Local write is synchronous; the remote
                            // push does not block the pop.
                            prefs.save(minutes)
                            readingGoalRepository?.let { repository ->
                                saveScope.launch { repository.sync(activeUser.currentUserId()) }
                            }
                            nestedNavController.popBackStack()
                        },
                        onNavigateBack = { nestedNavController.popBackStack() },
                    )
                }
            }

            // PP-1: destination registered only in debug builds.
            if (BuildConfig.DEBUG) {
                composable(route = NexoDestination.SettingsPerformance.route) {
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
    NexoTheme(darkTheme = true) {
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
            activeUser = ActiveUserProvider().apply { set("local-1") },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenLightPreview() {
    NexoTheme(darkTheme = false) {
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
            activeUser = ActiveUserProvider().apply { set("local-1") },
        )
    }
}
