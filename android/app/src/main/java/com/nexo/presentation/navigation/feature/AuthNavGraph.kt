package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nexo.di.AppContainer
import com.nexo.presentation.feature.auth.AuthScreen
import com.nexo.presentation.feature.auth.ForgotScreen
import com.nexo.presentation.feature.auth.RegisterScreen
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Feature NavGraph for auth: 3 routes (auth, auth/register, auth/forgot).
 *
 * Receives host-scoped [authViewModel] and [appContainer] (for readingGoalPreferences).
 * Never calls viewModel() inside composable — VM scoping stays host-owned.
 * Preserves NexoDestination strings, fade transitions, and popUpTo(Auth){inclusive=true}
 * verbatim from the monolith.
 */
fun NavGraphBuilder.authGraph(
    navController: NavController,
    authViewModel: AuthViewModel,
    appContainer: AppContainer,
) {
    @Composable
    fun onAuthenticated(): () -> Unit {
        val scope = rememberCoroutineScope()
        return { scope.launch { navigateAfterAuthentication(navController, appContainer, authViewModel) } }
    }

    composable(
        route = NexoDestination.Auth.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        AuthScreen(
            viewModel = authViewModel,
            onAuthenticated = onAuthenticated(),
            onContinueLocal = {
                authViewModel.continueLocally()
                // Decision D5: the anonymous/local session owns nothing, so it
                // never has a persisted goal and always runs the goal step.
                navController.navigate(NexoDestination.OnboardingGoal.route) {
                    popUpTo(NexoDestination.Auth.route) { inclusive = true }
                }
            },
            onNavigateToRegister = {
                navController.navigate(NexoDestination.AuthRegister.route) {
                    launchSingleTop = true
                }
            },
            onNavigateToForgot = {
                navController.navigate(NexoDestination.AuthForgot.route) {
                    launchSingleTop = true
                }
            },
        )
    }

    composable(
        route = NexoDestination.AuthRegister.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        RegisterScreen(
            viewModel = authViewModel,
            onAuthenticated = onAuthenticated(),
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(
        route = NexoDestination.AuthForgot.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        ForgotScreen(
            viewModel = authViewModel,
            onAuthenticated = onAuthenticated(),
            onNavigateBack = { navController.popBackStack() },
        )
    }
}

/**
 * Navigates a freshly authenticated account to its landing route: onboarding
 * when it has no goal yet, Home otherwise (REQ-daily-reading-goal-2).
 *
 * ## Why this waits instead of reading a value
 *
 * The account id is read from the session the auth flow just published, NOT from
 * [com.nexo.domain.session.ActiveUserProvider]. The provider is written by
 * `SessionSyncEffect`, which reacts to the same auth-state change as the
 * `onAuthenticated` callback; both are `LaunchedEffect`s and their order is not
 * guaranteed, so reading the provider here could resolve the *previous* account
 * and send a returning user through onboarding.
 *
 * Asking the ViewModel directly removes that race, but only if the answer is
 * actually present. The callback can fire one frame before the session lands in
 * `authViewModel.uiState`, and an absent session is indistinguishable from "this
 * account has no goal" — both would route to onboarding, which is the flash this
 * function exists to prevent. So it waits for the session to appear, and
 * navigates from the account it names.
 */
private suspend fun navigateAfterAuthentication(
    navController: NavController,
    appContainer: AppContainer,
    authViewModel: AuthViewModel,
) {
    val userId =
        authViewModel.uiState
            .map { it.currentSession?.userId }
            .filterNotNull()
            .first()
    val hasGoal = appContainer.readingGoalPreferences.load(userId) != null
    val destination = if (hasGoal) NexoDestination.Home.route else NexoDestination.OnboardingGoal.route
    navController.navigate(destination) {
        popUpTo(NexoDestination.Auth.route) { inclusive = true }
    }
}
