package com.nexo.domain.notification

/** What a notification is about: routine system events vs. engagement nudges. */
enum class Category {
    SYSTEM,
    NUDGE,
}

/** How important a notification is; drives nothing by itself (category + interruption do). */
enum class Severity {
    SUCCESS,
    INFO,
    WARNING,
    ERROR,
}

/** How much a notification may interrupt the reader. */
enum class Interruption {
    SILENT,
    IN_APP,
    SYSTEM,
}

/** The subsystem that produced the notification. */
enum class NotificationSource {
    IMPORT,
    SYNC,
    ADDONS,
    UPDATE,
    LIBRARY,
    STREAK,
    GOAL,
}

/** Destinations a notification can navigate to in the app router. */
enum class NotificationRouteTarget {
    HOME,
    LIBRARY,
    SYNC,
    STORAGE,
    ADDONS,
    SETTINGS,
}

/** Where activating a notification navigates to. */
sealed interface NotificationTarget {
    /** Opens a specific book's details. */
    data class Book(
        val bookId: String,
    ) : NotificationTarget

    /** Navigates to a known route destination. */
    data class Route(
        val route: NotificationRouteTarget,
    ) : NotificationTarget
}

/**
 * One user-visible event the reader could have missed.
 *
 * [i18nKey] is a message KEY rendered at display time, never a resolved string,
 * so a later language switch re-renders history correctly.
 */
data class Notification(
    val id: String,
    val createdAt: Long,
    val source: NotificationSource,
    val category: Category,
    val severity: Severity,
    val interruption: Interruption,
    val i18nKey: String,
    val i18nParams: Map<String, String>? = null,
    val target: NotificationTarget? = null,
    val readAt: Long? = null,
    val dedupKey: String? = null,
)
