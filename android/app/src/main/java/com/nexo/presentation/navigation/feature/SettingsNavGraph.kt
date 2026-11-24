package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nexo.data.remote.addons.AddonRegistry
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.data.repository.ReadingGoalRepository
import com.nexo.data.session.DefinitionPreferences
import com.nexo.data.session.NotificationPreferencesStore
import com.nexo.data.session.ReaderPreferences
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.session.TranslationPreferences
import com.nexo.debug.LogViewerScreen
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.ThemeMode
import com.nexo.domain.repository.CacheRepository
import com.nexo.domain.repository.DefinitionHistoryRepository
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.repository.StorageRepository
import com.nexo.domain.repository.TranslationHistoryRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.presentation.feature.legal.AddonCapabilityDetailRoute
import com.nexo.presentation.feature.legal.LegalPolicyScreen
import com.nexo.presentation.feature.legal.addonCapabilitiesRoute
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.screen.SettingsScreen
import com.nexo.presentation.screen.settings.AddonManagementRoute
import com.nexo.presentation.screen.settings.LicensesScreen
import com.nexo.presentation.screen.settings.PrivacyPolicyScreen
import com.nexo.presentation.screen.settings.TermsScreen
import com.nexo.presentation.viewmodel.AuthViewModel
import com.nexo.presentation.viewmodel.StatisticsViewModel
import com.nexo.presentation.viewmodel.UpdateViewModel

/**
 * Feature NavGraph for Settings + LogViewer.
 *
 * Threads currentSession, settingsInitialRoute, appThemeMode verbatim.
 * Builders receive host VMs, never call viewModel() inside composable.
 * Preserves all route strings, popUpTo inclusive, and theme callbacks.
 */
fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    readerPreferences: ReaderPreferences,
    readingGoalPreferences: ReadingGoalPreferences,
    readingGoalRepository: ReadingGoalRepository,
    notificationPreferencesStore: NotificationPreferencesStore,
    storageRepository: StorageRepository,
    cacheRepository: CacheRepository,
    libraryRepository: LibraryRepository,
    activeUserProvider: ActiveUserProvider,
    translationPreferences: TranslationPreferences,
    translationHistoryRepository: TranslationHistoryRepository,
    definitionHistoryRepository: DefinitionHistoryRepository,
    definitionPreferences: DefinitionPreferences,
    addonRegistry: AddonRegistry,
    authViewModel: AuthViewModel,
    statisticsViewModel: StatisticsViewModel,
    dictionaryRepository: DictionaryRepository,
    driveAuthHelper: GoogleDriveAuthHelper,
    currentSession: AuthSession?,
    settingsInitialRoute: String?,
    onSettingsInitialRouteConsumed: () -> Unit,
    appThemeMode: ThemeMode,
    onAppThemeModeChanged: (ThemeMode) -> Unit,
    contentPadding: PaddingValues,
    updateViewModel: UpdateViewModel,
) {
    composable(
        route = NexoDestination.Settings.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        SettingsScreen(
            contentPadding = contentPadding,
            authSession = currentSession,
            initialRoute = settingsInitialRoute,
            onInitialRouteConsumed = onSettingsInitialRouteConsumed,
            appThemeMode = appThemeMode,
            onAppThemeModeChanged = onAppThemeModeChanged,
            // Only signs out: NexoNavHost navigates to the login once the session
            // is actually cleared. Navigating here raced the async sign-out.
            onLogout = { authViewModel.signOut() },
            customHighlightColors = readerPreferences.load().customHighlightColors,
            onUpdateCustomHighlightColor = { index, hex ->
                val prefs = readerPreferences
                val current = prefs.load()
                val colors =
                    current.customHighlightColors?.toMutableList()
                        ?: com.nexo.domain.model.HighlightColor
                            .defaultHexList()
                            .toMutableList()
                if (index in colors.indices) colors[index] = hex
                prefs.save(current.copy(customHighlightColors = colors))
            },
            onAddCustomHighlightColor = {
                val prefs = readerPreferences
                val current = prefs.load()
                val colors =
                    current.customHighlightColors?.toMutableList()
                        ?: com.nexo.domain.model.HighlightColor
                            .defaultHexList()
                            .toMutableList()
                if (colors.size < 5) {
                    colors.add(com.nexo.domain.model.HighlightColor.YELLOW.hex)
                    prefs.save(current.copy(customHighlightColors = colors))
                }
            },
            onDeleteCustomHighlightColor = { index ->
                val prefs = readerPreferences
                val current = prefs.load()
                val colors =
                    current.customHighlightColors?.toMutableList()
                        ?: com.nexo.domain.model.HighlightColor
                            .defaultHexList()
                            .toMutableList()
                if (colors.size > 3 && index in colors.indices) {
                    colors.removeAt(index)
                    prefs.save(current.copy(customHighlightColors = colors))
                }
            },
            onResetCustomHighlightColors = {
                val prefs = readerPreferences
                val current = prefs.load()
                prefs.save(current.copy(customHighlightColors = null))
            },
            onNavigateToLogViewer = {
                navController.navigate(NexoDestination.LogViewer.route)
            },
            statisticsViewModel = statisticsViewModel,
            dictionaryRepository = dictionaryRepository,
            driveAuthHelper = driveAuthHelper,
            readingGoalPreferences = readingGoalPreferences,
            readingGoalRepository = readingGoalRepository,
            notificationPreferencesStore = notificationPreferencesStore,
            storageRepository = storageRepository,
            cacheRepository = cacheRepository,
            libraryRepository = libraryRepository,
            activeUser = activeUserProvider,
            translationPreferences = translationPreferences,
            translationHistoryRepository = translationHistoryRepository,
            definitionPreferences = definitionPreferences,
            definitionHistoryRepository = definitionHistoryRepository,
            updateViewModel = updateViewModel,
            onNavigateToAddons = {
                navController.navigate(NexoDestination.SettingsAddons.route)
            },
        )
    }

    composable(route = NexoDestination.LogViewer.route) {
        LogViewerScreen(
            onBack = { navController.popBackStack() },
        )
    }

    composable(route = NexoDestination.SettingsAddons.route) {
        StatusBarInset {
            AddonManagementRoute(
                registry = addonRegistry,
                onBack = { navController.popBackStack() },
                hasConsent = { addonId -> addonRegistry.hasAddonConsent(addonId) },
                onConsentChange = { addonId, granted ->
                    if (granted) {
                        addonRegistry.recordAddonConsent(addonId)
                    } else {
                        addonRegistry.revokeAddonConsent(addonId)
                    }
                },
                onNavigateToLegal = {
                    navController.navigate(NexoDestination.SettingsLegal.route)
                },
                onOpenCapabilities = { addonId ->
                    navController.navigate(addonCapabilitiesRoute(addonId))
                },
            )
        }
    }

    composable(route = NexoDestination.SettingsLegal.route) {
        LegalPolicyScreen(
            onBack = { navController.popBackStack() },
        )
    }

    composable(route = NexoDestination.SettingsPrivacy.route) {
        StatusBarInset {
            PrivacyPolicyScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }

    composable(route = NexoDestination.SettingsTerms.route) {
        StatusBarInset {
            TermsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }

    composable(route = NexoDestination.SettingsLicenses.route) {
        StatusBarInset {
            LicensesScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }

    composable(
        route = NexoDestination.SettingsAddonCapabilities.route,
        arguments = listOf(navArgument("addonId") { type = NavType.StringType }),
    ) { entry ->
        AddonCapabilityDetailRoute(
            registry = addonRegistry,
            addonId = entry.arguments?.getString("addonId").orEmpty(),
            hasConsent = { addonId -> addonRegistry.hasAddonConsent(addonId) },
            onConsentChange = { addonId, granted ->
                if (granted) {
                    addonRegistry.recordAddonConsent(addonId)
                } else {
                    addonRegistry.revokeAddonConsent(addonId)
                }
            },
            onViewPolicy = {
                navController.navigate(NexoDestination.SettingsLegal.route)
            },
            onBack = { navController.popBackStack() },
        )
    }
}

/**
 * Root-graph pages built on `NexoSettingsSubPage`. That frame zeroes its top bar
 * insets because inside SettingsScreen's nested host the status bar is already
 * padded; reached from this root graph nothing pads it, and the title drew under
 * the status bar. The same pages inside Settings keep their frame untouched.
 */
@Composable
private fun StatusBarInset(content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding(),
    ) {
        content()
    }
}
