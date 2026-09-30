package com.nexo.presentation.navigation.feature

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nexo.di.AppContainer
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.screen.OnboardingGoalScreen

/**
 * Feature NavGraph for onboarding/goal.
 *
 * Preserves NexoDestination strings verbatim; no VM creation inside builders.
 * onSave writes goal + bumps dailyGoalVersion (host-owned) then navigates to Home
 * with popUpTo(0){inclusive=true} as in the monolith.
 */
fun NavGraphBuilder.onboardingGraph(
    navController: NavController,
    appContainer: AppContainer,
    onGoalSaved: () -> Unit,
) {
    composable(
        route = NexoDestination.OnboardingGoal.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        OnboardingGoalScreen(
            onSave = { minutes ->
                appContainer.readingGoalPreferences.save(minutes)
                onGoalSaved()
                navController.navigate(NexoDestination.Home.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
