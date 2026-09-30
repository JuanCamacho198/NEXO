package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nexo.data.remote.drive.GoogleDriveAuthHelper
import com.nexo.domain.model.AuthSession
import com.nexo.presentation.feature.library.LibraryScreen
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.viewmodel.LibraryViewModel

/**
 * Feature NavGraph for Library (bookshelf).
 *
 * Receives host [libraryViewModel] + [currentSession] threading verbatim.
 * onSelectBook write lambda keeps selectedBook* in host rememberSaveable.
 */
fun NavGraphBuilder.libraryGraph(
    navController: NavController,
    libraryViewModel: LibraryViewModel,
    driveAuthHelper: GoogleDriveAuthHelper,
    currentSession: AuthSession?,
    contentPadding: PaddingValues,
    onSelectBook: (String, String?, String) -> Unit,
    onSettingsInitialRoute: (String) -> Unit,
) {
    composable(
        route = NexoDestination.Library.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        LibraryScreen(
            contentPadding = contentPadding,
            viewModel = libraryViewModel,
            driveAuthHelper = driveAuthHelper,
            authSession = currentSession,
            onOpenAccount = {
                onSettingsInitialRoute(NexoDestination.SettingsAccount.route)
                navController.navigate(NexoDestination.Settings.route) { launchSingleTop = true }
            },
            onBookSelected = { bookId, filePath, format ->
                onSelectBook(bookId, filePath, format)
                navController.navigate("book_detail/$bookId")
            },
            onEditBook = { bookId ->
                navController.navigate("book_edit/$bookId")
            },
        )
    }
}
