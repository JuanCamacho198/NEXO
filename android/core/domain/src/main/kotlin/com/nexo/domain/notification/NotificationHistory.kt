package com.nexo.domain.notification

import kotlinx.coroutines.flow.Flow

/**
 * Storage contract the [NotificationStore] needs from the history layer.
 *
 * Narrow on purpose so the store stays testable on the JVM without Room: the
 * Room-backed `NotificationRepository` implements it, and tests supply an
 * in-memory fake. Everything the policy snapshot needs (current dedup keys) and
 * everything the tray needs (the entries) flows through this one interface.
 */
interface NotificationHistory {
    /** Chronological (oldest-first) tray entries, newest retention applied. */
    val items: Flow<List<Notification>>

    /** Live unread count for the bell badge. */
    val unreadCount: Flow<Int>

    /** Loads persisted history into the in-memory tray. Call before first emit. */
    suspend fun load()

    /** Persists one notification and folds it into the tray view. */
    suspend fun save(notification: Notification)

    /** Marks one entry read; a missing or already-read id is a no-op. */
    suspend fun markRead(
        id: String,
        readAt: Long = System.currentTimeMillis(),
    )

    /** Marks every unread entry read. */
    suspend fun markAllRead(readAt: Long = System.currentTimeMillis())
}

/**
 * A draft the emit sites hand to [NotificationStore]: everything the policy
 * needs that is NOT ambient (clock, foreground, reading, snapshot, preferences
 * are supplied by the store itself).
 *
 * [id] lets a caller reuse a deterministic id (e.g. `"import:$title"`); when
 * omitted the store mints one from the source + timestamp.
 */
data class NotifyDraft(
    val source: NotificationSource,
    val severity: Severity,
    val i18nKey: String,
    val id: String? = null,
    val i18nParams: Map<String, String>? = null,
    val target: NotificationTarget? = null,
    val dedupKey: String? = null,
    val category: Category? = null,
    val interruption: Interruption? = null,
)
