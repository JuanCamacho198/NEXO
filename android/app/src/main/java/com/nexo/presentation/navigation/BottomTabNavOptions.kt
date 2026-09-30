package com.nexo.presentation.navigation

import androidx.navigation.NavController

/**
 * Navigation options applied when a bottom-navigation tab is selected.
 *
 * The Inicio (Home) tab is special-cased: tapping it must always collapse the
 * back stack to `[home]` from any other tab. To do that we pop up to the Home
 * route (non-inclusive), launch single-top, and deliberately do NOT use
 * [restoreState]/[saveState] — restoring the previous tab's saved sub-stack
 * re-instantiates a stale stack, and popping to the graph's
 * `startDestinationId` is a no-op when the start destination is "auth"
 * (absent from the stack), which is the root cause of the stuck bottom nav.
 *
 * Every other tab keeps the classic bottom-nav behavior: [restoreState] +
 * [saveState] around a non-inclusive [popUpToRoute] so each tab preserves its
 * own sub-stack while the stack still collapses on an Inicio tap.
 *
 * @property popUpToRoute Route to pop up to (non-inclusive) before navigating.
 * @property popUpToInclusive Whether [popUpToRoute] itself is popped.
 * @property launchSingleTop Avoids duplicate entries when the target is already on top.
 * @property restoreState Restores the tab's previously saved state (Home never restores).
 * @property saveState Saves the state of the entries popped by [popUpToRoute].
 */
data class BottomTabNavOptions(
    val popUpToRoute: String,
    val popUpToInclusive: Boolean = false,
    val launchSingleTop: Boolean = true,
    val restoreState: Boolean = false,
    val saveState: Boolean = false,
) {
    companion object {
        /**
         * Options for selecting [route] from the bottom nav.
         *
         * @param route The selected tab's route.
         * @param homeRoute The route of the Home (Inicio) tab.
         */
        fun forRoute(
            route: String,
            homeRoute: String,
        ): BottomTabNavOptions =
            if (route == homeRoute) {
                // Inicio: collapse to [home]; no save/restore of the previous tab.
                BottomTabNavOptions(
                    popUpToRoute = homeRoute,
                    popUpToInclusive = false,
                    launchSingleTop = true,
                    restoreState = false,
                    saveState = false,
                )
            } else {
                // Other tabs: classic bottom-nav save/restore around Home.
                BottomTabNavOptions(
                    popUpToRoute = homeRoute,
                    popUpToInclusive = false,
                    launchSingleTop = true,
                    restoreState = true,
                    saveState = true,
                )
            }
    }
}

/**
 * Navigates to a bottom-nav [route] applying [BottomTabNavOptions.forRoute],
 * so the Inicio tab collapses the stack to [homeRoute] while every other tab
 * keeps its save/restore semantics.
 */
fun NavController.navigateToBottomTab(
    route: String,
    homeRoute: String,
) {
    val options = BottomTabNavOptions.forRoute(route, homeRoute)
    navigate(route) {
        launchSingleTop = options.launchSingleTop
        restoreState = options.restoreState
        popUpTo(options.popUpToRoute) {
            inclusive = options.popUpToInclusive
            saveState = options.saveState
        }
    }
}
