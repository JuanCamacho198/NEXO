package com.nexo.presentation.navigation

import com.nexo.domain.notification.Notification
import com.nexo.domain.notification.NotificationRouteTarget
import com.nexo.domain.notification.NotificationTarget

/**
 * A notification target that RESOLVED to a real in-app destination.
 *
 * Resolution is a pure mapping ([resolveNotificationTarget]) so the tray's
 * "does this entry navigate?" decision is unit-testable: `null` means the entry
 * stays on the tray as a safe no-op (an unreadable target, an unknown route, or
 * a blank book id must never crash or navigate to a dead route).
 */
sealed interface ResolvedNotificationTarget {
    data class Book(
        val bookId: String,
    ) : ResolvedNotificationTarget

    /**
     * A route destination. [rootRoute] is what the root NavHost navigates to;
     * [settingsInitialRoute] is the nested Settings sub-route the Settings host
     * should open (the nested routes live inside the `settings` graph, so they
     * cannot be navigated to directly from the root).
     */
    data class Route(
        val rootRoute: NexoDestination,
        val settingsInitialRoute: String? = null,
    ) : ResolvedNotificationTarget
}

/**
 * Maps a notification's [NotificationTarget] to a concrete destination, or
 * `null` when it cannot be resolved.
 *
 * `NotificationTarget.Book` resolves only for a non-blank id (the caller
 * additionally verifies the book still exists before navigating).
 * `NotificationTarget.Route` resolves through an explicit table so an added
 * enum value that has no in-app destination degrades to a no-op instead of a
 * route string the NavHost does not know. Settings sub-pages resolve to the
 * settings root plus the nested initial route.
 */
fun resolveNotificationTarget(notification: Notification): ResolvedNotificationTarget? =
    when (val target = notification.target) {
        is NotificationTarget.Book -> target.bookId.takeIf { it.isNotBlank() }?.let { ResolvedNotificationTarget.Book(it) }
        is NotificationTarget.Route -> routeDestination(target.route)
        null -> null
    }

private val SETTINGS_SUB_ROUTES: Map<NotificationRouteTarget, NexoDestination> =
    mapOf(
        NotificationRouteTarget.SYNC to NexoDestination.SettingsSync,
        NotificationRouteTarget.STORAGE to NexoDestination.SettingsStorage,
        NotificationRouteTarget.ADDONS to NexoDestination.SettingsAddons,
    )

private fun routeDestination(route: NotificationRouteTarget): ResolvedNotificationTarget.Route =
    when (route) {
        NotificationRouteTarget.HOME -> ResolvedNotificationTarget.Route(NexoDestination.Home)
        NotificationRouteTarget.LIBRARY -> ResolvedNotificationTarget.Route(NexoDestination.Library)
        NotificationRouteTarget.SETTINGS -> ResolvedNotificationTarget.Route(NexoDestination.Settings)
        NotificationRouteTarget.SYNC,
        NotificationRouteTarget.STORAGE,
        NotificationRouteTarget.ADDONS,
        ->
            ResolvedNotificationTarget.Route(
                rootRoute = NexoDestination.Settings,
                settingsInitialRoute = SETTINGS_SUB_ROUTES.getValue(route).route,
            )
    }
