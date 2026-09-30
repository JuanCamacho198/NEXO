package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nexo.presentation.feature.home.HomeScreen
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.navigation.ReaderRoute
import com.nexo.presentation.navigation.rememberImportLauncher
import com.nexo.presentation.screen.library.RemoveBookDialog
import com.nexo.presentation.viewmodel.HomeViewModel
import com.nexo.presentation.viewmodel.LibraryViewModel

/**
 * Feature NavGraph for Home.
 *
 * Holds the hoisted [rememberImportLauncher] and delegates import via onImportBook.
 * Receives host VMs and write lambdas; never calls viewModel() inside composable.
 */
fun NavGraphBuilder.homeGraph(
    navController: NavController,
    homeViewModel: HomeViewModel,
    libraryViewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    onSelectBook: (String, String?, String) -> Unit,
    onSettingsInitialRoute: (String) -> Unit,
) {
    composable(
        route = NexoDestination.Home.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        val importLauncher = rememberImportLauncher(libraryViewModel)
        // Delete-dialog state lives in LibraryViewModel (shared shelf menu backend).
        // It must be hosted on the Home route too — LibraryScreen's RemoveBookDialog
        // is only composed on the Library route. Snackbar/share intents need no
        // extra host: NexoNavHost.GlobalEventCollector already collects
        // libraryViewModel.uiEvent globally.
        val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()

        HomeScreen(
            contentPadding = contentPadding,
            viewModel = homeViewModel,
            onNavigateToLibrary = {
                navController.navigate(NexoDestination.Library.route) { launchSingleTop = true }
            },
            onNavigateToHighlights = {
                navController.navigate(NexoDestination.Highlights.route) { launchSingleTop = true }
            },
            onNavigateToSettings = {
                navController.navigate(NexoDestination.Settings.route) { launchSingleTop = true }
            },
            onOpenAccount = {
                onSettingsInitialRoute(NexoDestination.SettingsAccount.route)
                navController.navigate(NexoDestination.Settings.route) { launchSingleTop = true }
            },
            onNavigateToStatistics = {
                navController.navigate(NexoDestination.Statistics.route)
            },
            onBookSelected = { bookId, filePath, format ->
                onSelectBook(bookId, filePath, format)
                navController.navigate("book_detail/$bookId")
            },
            onContinueReading = { bookId, filePath, format ->
                onSelectBook(bookId, filePath, format)
                navController.navigate(ReaderRoute(bookId, filePath, format)) {
                    popUpTo<ReaderRoute> { inclusive = true }
                }
            },
            onImportBook = {
                importLauncher.launch(arrayOf("application/epub+zip", "application/pdf"))
            },
            onEditBook = { book ->
                navController.navigate("book_edit/${book.id}")
            },
            onMarkCompleted = libraryViewModel::onMenuMarkCompleted,
            onMarkPlanToRead = libraryViewModel::onMenuMarkPlanToRead,
            onShareBook = libraryViewModel::onMenuShare,
            onRequestDeleteBook = libraryViewModel::requestDeleteBook,
        )
        RemoveBookDialog(
            bookToDelete = libraryUiState.bookToDelete,
            onDismiss = libraryViewModel::dismissDeleteDialog,
            onConfirmLocalOnly = libraryViewModel::confirmDeleteLocalOnly,
            onConfirmLocalAndDrive = libraryViewModel::confirmDeleteLocalAndDrive,
        )
    }
}
