package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nexo.di.AppContainer
import com.nexo.presentation.feature.auth.AuthScreen
import com.nexo.presentation.feature.auth.ForgotScreen
import com.nexo.presentation.feature.auth.RegisterScreen
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.viewmodel.AuthViewModel

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
    composable(
        route = NexoDestination.Auth.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        AuthScreen(
            viewModel = authViewModel,
            onAuthenticated = {
                val destination =
                    if (appContainer.readingGoalPreferences.load() == null) {
                        NexoDestination.OnboardingGoal.route
                    } else {
                        NexoDestination.Home.route
                    }
                navController.navigate(destination) {
                    popUpTo(NexoDestination.Auth.route) { inclusive = true }
                }
            },
            onContinueLocal = {
                authViewModel.continueLocally()
                val destination =
                    if (appContainer.readingGoalPreferences.load() == null) {
                        NexoDestination.OnboardingGoal.route
                    } else {
                        NexoDestination.Home.route
                    }
                navController.navigate(destination) {
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
            onAuthenticated = {
                val destination =
                    if (appContainer.readingGoalPreferences.load() == null) {
                        NexoDestination.OnboardingGoal.route
                    } else {
                        NexoDestination.Home.route
                    }
                navController.navigate(destination) {
                    popUpTo(NexoDestination.Auth.route) { inclusive = true }
                }
            },
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
            onAuthenticated = {
                val destination =
                    if (appContainer.readingGoalPreferences.load() == null) {
                        NexoDestination.OnboardingGoal.route
                    } else {
                        NexoDestination.Home.route
                    }
                navController.navigate(destination) {
                    popUpTo(NexoDestination.Auth.route) { inclusive = true }
                }
            },
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
