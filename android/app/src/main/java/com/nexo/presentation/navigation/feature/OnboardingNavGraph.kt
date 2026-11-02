package com.nexo.presentation.navigation.feature

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nexo.data.session.NotificationPermissionPrefs
import com.nexo.di.AppContainer
import com.nexo.presentation.navigation.NexoDestination
import com.nexo.presentation.screen.OnboardingGoalScreen
import com.nexo.presentation.screen.OnboardingNotificationsScreen
import com.nexo.presentation.screen.appDetailsSettingsIntent
import com.nexo.presentation.screen.notificationSettingsIntent
import kotlinx.coroutines.launch

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
    onOnboardingCompleted: () -> Unit,
) {
    composable(
        route = NexoDestination.OnboardingGoal.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        val saveScope = rememberCoroutineScope()
        OnboardingGoalScreen(
            onSave = { minutes ->
                // Local write synchronously first, so the cache is authoritative
                // for the rest of the flow. The remote push is backgrounded: a
                // slow or failed network must not block navigation, and the
                // local cache already answers offline.
                appContainer.readingGoalPreferences.save(minutes)
                saveScope.launch {
                    appContainer.readingGoalRepository.sync(appContainer.activeUserProvider.currentUserId())
                }
                // onGoalSaved bumps the host's gate key so it recomputes from the
                // new goal — but the host's `startDestination` would then flip to
                // Home, overriding this navigate. Do NOT bump yet: the gate stays
                // on its pre-goal answer until the notifications step finishes
                // (`onOnboardingCompleted`), which is the single point that ends
                // onboarding. Without this ordering the notifications screen was
                // skipped entirely.
                navController.navigate(NexoDestination.OnboardingNotifications.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
            onNavigateBack = { navController.popBackStack() },
        )
    }

    composable(
        route = NexoDestination.OnboardingNotifications.route,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        val context = LocalContext.current
        // Read ONCE: the screen's "blocked" copy must reflect the state at
        // decision time, not flip mid-animation when the launcher returns.
        val blocked = remember { NotificationPermissionPrefs.isBlocked(context) }

        val permissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) NotificationPermissionPrefs.markDenied(context)
                finishOnboarding(navController, onOnboardingCompleted)
            }

        OnboardingNotificationsScreen(
            blocked = blocked,
            onEnable = {
                // Record BEFORE launching so a dismissal without an answer still
                // counts as "asked" (no nagging on a later launch). The launcher
                // itself is one-shot: this screen is the only caller and it does
                // not re-show after the flow.
                NotificationPermissionPrefs.markRequested(context)
                if (NotificationPermissionPrefs.isRuntimePermissionRequired()) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    finishOnboarding(navController, onOnboardingCompleted)
                }
            },
            onSkip = {
                // Skipping still counts as asked: the user made a decision, so
                // the OS dialog is never fired and never re-asked automatically.
                NotificationPermissionPrefs.markRequested(context)
                finishOnboarding(navController, onOnboardingCompleted)
            },
            onOpenSettings = {
                val intent = notificationSettingsIntent(context)
                runCatching { context.startActivity(intent) }
                    .recoverCatching { context.startActivity(appDetailsSettingsIntent(context)) }
                    .onFailure {
                        // No settings activity on the device (rare); finish the
                        // flow rather than trapping the user on this screen.
                        if (it is ActivityNotFoundException) finishOnboarding(navController, onOnboardingCompleted)
                    }
            },
        )
    }
}

/** Leaves onboarding for Home, clearing the whole onboarding back stack. */
private fun finishOnboarding(
    navController: NavController,
    onOnboardingCompleted: () -> Unit,
) {
    // The single gate-ending signal: from here the account is onboarded.
    onOnboardingCompleted()
    navController.navigate(NexoDestination.Home.route) {
        popUpTo(0) { inclusive = true }
    }
}
