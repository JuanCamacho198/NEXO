package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.debug.LogViewerScreen
import com.nexo.di.AppContainer
import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.ThemeMode
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.presentation.feature.legal.AddonCapabilityDetailRoute
import com.nexo.presentation.feature.legal.LegalPolicyScreen
import com.nexo.presentation.feature.legal.addonCapabilitiesRoute
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.screen.SettingsScreen
import com.nexo.presentation.screen.settings.AddonManagementRoute
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
    appContainer: AppContainer,
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
            onLogout = {
                authViewModel.signOut()
                navController.navigate(NexoDestination.Auth.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
            customHighlightColors = appContainer.readerPreferences.load().customHighlightColors,
            onUpdateCustomHighlightColor = { index, hex ->
                val prefs = appContainer.readerPreferences
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
                val prefs = appContainer.readerPreferences
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
                val prefs = appContainer.readerPreferences
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
                val prefs = appContainer.readerPreferences
                val current = prefs.load()
                prefs.save(current.copy(customHighlightColors = null))
            },
            onNavigateToLogViewer = {
                navController.navigate(NexoDestination.LogViewer.route)
            },
            statisticsViewModel = statisticsViewModel,
            dictionaryRepository = dictionaryRepository,
            driveAuthHelper = driveAuthHelper,
            readingGoalPreferences = appContainer.readingGoalPreferences,
            storageRepository = appContainer.storageRepository,
            cacheRepository = appContainer.cacheRepository,
            libraryRepository = appContainer.libraryRepository,
            updateViewModel = updateViewModel,
        )
    }

    composable(route = NexoDestination.LogViewer.route) {
        LogViewerScreen(
            onBack = { navController.popBackStack() },
        )
    }

    composable(route = NexoDestination.SettingsAddons.route) {
        AddonManagementRoute(
            registry = appContainer.addonRegistry,
            onBack = { navController.popBackStack() },
            hasConsent = { addonId -> appContainer.addonRegistry.hasAddonConsent(addonId) },
            onConsentChange = { addonId, granted ->
                if (granted) {
                    appContainer.addonRegistry.recordAddonConsent(addonId)
                } else {
                    appContainer.addonRegistry.revokeAddonConsent(addonId)
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

    composable(route = NexoDestination.SettingsLegal.route) {
        LegalPolicyScreen(
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = NexoDestination.SettingsAddonCapabilities.route,
        arguments = listOf(navArgument("addonId") { type = NavType.StringType }),
    ) { entry ->
        AddonCapabilityDetailRoute(
            registry = appContainer.addonRegistry,
            addonId = entry.arguments?.getString("addonId").orEmpty(),
            hasConsent = { addonId -> appContainer.addonRegistry.hasAddonConsent(addonId) },
            onConsentChange = { addonId, granted ->
                if (granted) {
                    appContainer.addonRegistry.recordAddonConsent(addonId)
                } else {
                    appContainer.addonRegistry.revokeAddonConsent(addonId)
                }
            },
            onViewPolicy = {
                navController.navigate(NexoDestination.SettingsLegal.route)
            },
            onBack = { navController.popBackStack() },
        )
    }
}
