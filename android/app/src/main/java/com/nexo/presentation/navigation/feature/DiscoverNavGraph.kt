package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.nexo.R
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.domain.discover.DiscoverRailState
import com.nexo.domain.usecase.DownloadAndImportBookUseCase
import com.nexo.presentation.feature.discover.DiscoverScreen
import com.nexo.presentation.feature.discover.DiscoverSectionScreen
import com.nexo.presentation.feature.legal.LegalDisclaimerGate
import com.nexo.presentation.feature.library.formatFileSize
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
        // The one-time legal disclaimer is hosted here rather than inside
        // DiscoverScreen: its copy is an `:app` string and its acceptance gate
        // reads `LegalDisclaimerPrefs`, neither of which :feature:discover can
        // reach. The gate reports its state up so the screen can stay hidden
        // until acceptance, keeping the pre-acceptance frame byte-identical to
        // the old behaviour.
        val disclaimerAccepted =
            LegalDisclaimerGate(
                onNavigateToLegalPolicy = {
                    navController.navigate(NexoDestination.SettingsLegal.route)
                },
            )
        DiscoverScreen(
            contentPadding = contentPadding,
            viewModel = discoverViewModel,
            userInitial = discoverUserInitial,
            contentHidden = !disclaimerAccepted,
            formatBytes = ::formatFileSize,
            onOpenSection = { rail: DiscoverRailState.Loaded, sectionTitle: String ->
                navController.navigate(
                    DiscoverSectionRoute(
                        sectionTitle = sectionTitle,
                        sort = rail.sort.orEmpty(),
                        // A topic shelf keeps its `?topic=` selector; only a plain
                        // per-source rail carries a free-text term.
                        sourceId = if (rail.topicTerm != null) "" else rail.sourceId.orEmpty(),
                        term = if (rail.topicTerm != null) "" else rail.topicTerm.orEmpty(),
                        topicTerm = rail.topicTerm.orEmpty(),
                    ),
                )
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
        val term = route.term
        val topicTerm = route.topicTerm.ifBlank { null }
        val sort = CatalogFeaturedSort.entries.firstOrNull { it.name == route.sort }

        val sectionViewModel =
            rememberDiscoverSectionViewModel(
                catalogProvider = catalogProvider,
                sectionTitle = sectionTitle,
                sort = sort,
                sourceId = sourceId,
                term = term,
                topicTerm = topicTerm,
                downloadAndImportBookUseCase = downloadAndImportBookUseCase,
            )
        DiscoverSectionScreen(
            contentPadding = contentPadding,
            viewModel = sectionViewModel,
            onBack = { navController.popBackStack() },
            backContentDescription = stringResource(R.string.discover_back),
        )
    }
}
