package com.nextpage.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nextpage.data.remote.drive.GoogleDriveAuthHelper
import com.nextpage.debug.LogViewerScreen
import com.nextpage.di.AppContainer
import com.nextpage.domain.model.AuthSession
import com.nextpage.domain.model.ThemeMode
import com.nextpage.domain.repository.DictionaryRepository
import com.nextpage.presentation.feature.legal.AddonCapabilityDetailRoute
import com.nextpage.presentation.feature.legal.LegalPolicyScreen
import com.nextpage.presentation.feature.legal.addonCapabilitiesRoute
import com.nextpage.presentation.navigation.NextPageDestination
import com.nextpage.presentation.screen.SettingsScreen
import com.nextpage.presentation.screen.settings.AddonManagementRoute
import com.nextpage.presentation.viewmodel.AuthViewModel
import com.nextpage.presentation.viewmodel.StatisticsViewModel
import com.nextpage.presentation.viewmodel.UpdateViewModel

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
        route = NextPageDestination.Settings.route,
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
                navController.navigate(NextPageDestination.Auth.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
            customHighlightColors = appContainer.readerPreferences.load().customHighlightColors,
            onUpdateCustomHighlightColor = { index, hex ->
                val prefs = appContainer.readerPreferences
                val current = prefs.load()
                val colors =
                    current.customHighlightColors?.toMutableList()
                        ?: com.nextpage.domain.model.HighlightColor
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
                        ?: com.nextpage.domain.model.HighlightColor
                            .defaultHexList()
                            .toMutableList()
                if (colors.size < 5) {
                    colors.add(com.nextpage.domain.model.HighlightColor.YELLOW.hex)
                    prefs.save(current.copy(customHighlightColors = colors))
                }
            },
            onDeleteCustomHighlightColor = { index ->
                val prefs = appContainer.readerPreferences
                val current = prefs.load()
                val colors =
                    current.customHighlightColors?.toMutableList()
                        ?: com.nextpage.domain.model.HighlightColor
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
                navController.navigate(NextPageDestination.LogViewer.route)
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

    composable(route = NextPageDestination.LogViewer.route) {
        LogViewerScreen(
            onBack = { navController.popBackStack() },
        )
    }

    composable(route = NextPageDestination.SettingsAddons.route) {
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
                navController.navigate(NextPageDestination.SettingsLegal.route)
            },
            onOpenCapabilities = { addonId ->
                navController.navigate(addonCapabilitiesRoute(addonId))
            },
        )
    }

    composable(route = NextPageDestination.SettingsLegal.route) {
        LegalPolicyScreen(
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = NextPageDestination.SettingsAddonCapabilities.route,
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
                navController.navigate(NextPageDestination.SettingsLegal.route)
            },
            onBack = { navController.popBackStack() },
        )
    }
}
