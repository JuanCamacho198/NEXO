package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.nexo.presentation.navigation.ReaderRoute
import com.nexo.presentation.screen.ReaderScreen
import com.nexo.presentation.viewmodel.ReaderViewModel

/**
 * Feature NavGraph for Reader.
 *
 * Holds no VM creation; receives host [readerViewModel] + selectedBook* values
 * as a backup. The book identity travels as the typed [ReaderRoute] navigation
 * arguments on the Reader destination; args win over the host snapshot, which
 * previously arrived blank because the NavHost composition captured
 * [selectedBookId] before the selection write landed. Preserves slide
 * transitions verbatim.
 */
fun NavGraphBuilder.readerGraph(
    navController: NavController,
    readerViewModel: ReaderViewModel,
    selectedBookId: String,
    selectedBookFilePath: String?,
    selectedBookFormat: String,
    contentPadding: PaddingValues,
) {
    composable<ReaderRoute>(
        enterTransition = { slideInHorizontally { it } + fadeIn() },
        exitTransition = { slideOutHorizontally { it } + fadeOut() },
        popEnterTransition = { slideInHorizontally { -it } + fadeIn() },
        popExitTransition = { slideOutHorizontally { -it } + fadeOut() },
    ) { backStackEntry ->
        val identity =
            resolveReaderBookIdentity(
                route = backStackEntry.toRoute<ReaderRoute>(),
                snapshotBookId = selectedBookId,
                snapshotBookPath = selectedBookFilePath,
                snapshotBookFormat = selectedBookFormat,
            )
        ReaderScreen(
            contentPadding = contentPadding,
            selectedBookId = identity.bookId,
            bookFilePath = identity.bookPath,
            bookFormat = identity.bookFormat,
            bookIdentitySource = identity.source,
            viewModel = readerViewModel,
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

/**
 * Book identity the Reader destination resolves for [ReaderScreen].
 *
 * [source] is the verbatim debug tag the screen logs: `"args"` when the typed
 * route carried a book id, `"snapshot"` when the host's `rememberSaveable`
 * backup was used.
 */
internal data class ReaderBookIdentity(
    val bookId: String,
    val bookPath: String?,
    val bookFormat: String,
    val source: String,
)

/**
 * Resolves the Reader's book identity from its typed route, falling back to the
 * host snapshot exactly as the string-route graph did.
 *
 * Args are fresh per destination entry (process-death safe); the host snapshot
 * is kept only as a backup for bare Reader navigations (blank [ReaderRoute.bookId]).
 * A blank `bookPath` normalises to `null`, matching the previous behaviour where
 * the route builder omitted it and the screen received `null`.
 */
internal fun resolveReaderBookIdentity(
    route: ReaderRoute,
    snapshotBookId: String,
    snapshotBookPath: String?,
    snapshotBookFormat: String,
): ReaderBookIdentity {
    val hasArgs = route.bookId.isNotBlank()
    return if (hasArgs) {
        ReaderBookIdentity(
            bookId = route.bookId,
            bookPath = route.bookPath?.takeIf { it.isNotBlank() },
            bookFormat = route.bookFormat,
            source = "args",
        )
    } else {
        ReaderBookIdentity(
            bookId = snapshotBookId,
            bookPath = snapshotBookPath,
            bookFormat = snapshotBookFormat,
            source = "snapshot",
        )
    }
}
