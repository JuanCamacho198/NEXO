package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.domain.usecase.DownloadAndImportBookUseCase
import com.nexo.presentation.feature.discover.DiscoverRailState
import com.nexo.presentation.feature.discover.DiscoverScreen
import com.nexo.presentation.feature.discover.DiscoverSectionScreen
import com.nexo.presentation.navigation.DiscoverSectionRoute
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.navigation.rememberDiscoverSectionViewModel
import com.nexo.presentation.viewmodel.DiscoverViewModel

/**
 * Feature NavGraph for Discover.
 *
 * Discover is the middle tab of the 5-tab bottom navigation
 * (`bottomNavDestinations`), so the shell is reachable directly from the bar.
 *
 * Two routes live here: the search shell and the "Ver todo" section list. The
 * section route carries the already-localized section title plus exactly one
 * selector (`sort` for a featured rail, `sourceId` for a per-source list), so no
 * resource-id round trip and no ambiguity about what is being paged.
 */
fun NavGraphBuilder.discoverGraph(
    navController: NavController,
    contentPadding: PaddingValues,
    discoverViewModel: DiscoverViewModel,
    catalogProvider: CatalogProvider,
    discoverUserInitial: String? = null,
    downloadAndImportBookUseCase: DownloadAndImportBookUseCase? = null,
) {
    composable(
        route = NexoDestination.Discover.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        DiscoverScreen(
            contentPadding = contentPadding,
            viewModel = discoverViewModel,
            userInitial = discoverUserInitial,
            onOpenSection = { rail: DiscoverRailState.Loaded, sectionTitle: String ->
                navController.navigate(
                    DiscoverSectionRoute(
                        sectionTitle = sectionTitle,
                        sort = rail.sort?.name.orEmpty(),
                        sourceId = rail.sourceId.orEmpty(),
                    ),
                )
            },
            onNavigateToSettingsAddons = {
                navController.navigate(NexoDestination.SettingsAddons.route)
            },
            onNavigateToLegalPolicy = {
                navController.navigate(NexoDestination.SettingsLegal.route)
            },
        )
    }

    composable<DiscoverSectionRoute>(
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) { entry ->
        val route = entry.toRoute<DiscoverSectionRoute>()
        val sectionTitle = route.sectionTitle
        val sourceId = route.sourceId.ifBlank { null }
        val sort = CatalogFeaturedSort.entries.firstOrNull { it.name == route.sort }

        val sectionViewModel =
            rememberDiscoverSectionViewModel(
                catalogProvider = catalogProvider,
                sectionTitle = sectionTitle,
                sort = sort,
                sourceId = sourceId,
                downloadAndImportBookUseCase = downloadAndImportBookUseCase,
            )
        DiscoverSectionScreen(
            contentPadding = contentPadding,
            viewModel = sectionViewModel,
            onBack = { navController.popBackStack() },
        )
    }
}
