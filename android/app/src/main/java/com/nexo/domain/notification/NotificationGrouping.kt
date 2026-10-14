package com.nexo.domain.notification

/**
 * Grouping rule for the notification tray (U6).
 *
 * Mirroring the desktop tray, entries that describe the SAME event across
 * occurrences collapse into one row keyed by `source::i18nKey`, with the NEWEST
 * entry (by `createdAt`, then id for stability) as the representative. The
 * group keeps every member so the row can render a count and the tray can mark
 * them all read on open.
 */
data class NotificationGroup(
    val key: String,
    val representative: Notification,
    val members: List<Notification>,
) {
    val count: Int get() = members.size

    val isUnread: Boolean get() = members.any { it.readAt == null }
}

/** The grouping key: two events with the same source AND key are one row. */
fun notificationGroupKey(notification: Notification): String = "${notification.source.name}::${notification.i18nKey}"

/**
 * Groups [items] by [notificationGroupKey] and orders groups newest-first by
 * their representative so the tray shows the freshest event on top.
 *
 * Pure and JVM-testable: no Android, no clock. The input order does not matter
 * (the representative is chosen by `createdAt`, not position), so a caller may
 * pass the repository's oldest-first [NotificationHistory.items] directly.
 */
fun groupNotifications(items: List<Notification>): List<NotificationGroup> =
    items
        .groupBy(::notificationGroupKey)
        .map { (key, members) ->
            val sorted = members.sortedWith(compareBy({ it.createdAt }, { it.id }))
            NotificationGroup(
                key = key,
                representative = sorted.last(),
                members = sorted,
            )
        }.sortedWith(
            compareByDescending<NotificationGroup> { it.representative.createdAt }
                .thenByDescending { it.representative.id },
        )
