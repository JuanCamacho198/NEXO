package com.nexo.data.repository

import com.nexo.data.local.dao.NotificationDao
import com.nexo.data.local.entity.NotificationEntity
import com.nexo.domain.notification.Category
import com.nexo.domain.notification.Interruption
import com.nexo.domain.notification.Notification
import com.nexo.domain.notification.NotificationRetention
import com.nexo.domain.notification.NotificationRouteTarget
import com.nexo.domain.notification.NotificationSource
import com.nexo.domain.notification.NotificationTarget
import com.nexo.domain.notification.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Room-backed notification history with retention (NOTIF-02).
 *
 * The store keeps an in-memory mirror ([items] / [unreadCount]) so the tray
 * view is a plain [Flow] over already-retained state, and persistence is a
 * best-effort mirror of that state — a failed write never breaks the tray.
 *
 * Ordering matches the desktop store: [items] is chronological (oldest-first),
 * capped at [MAX_NOTIFICATIONS] as a VIEW bound, while the durable bound is
 * [NotificationRetention] (200 rows / 30 days). `unreadCount` is the real
 * per-item count over the retained history.
 */
class NotificationRepository(
    private val dao: NotificationDao,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * The tray. Retained history oldest-first; newest-first storage order is
     * restored by [NotificationRetention.apply]. Capped at [MAX_NOTIFICATIONS].
     */
    val items: Flow<List<Notification>> get() = _items

    /** Live unread count over the retained history. */
    val unreadCount: Flow<Int> get() = _unreadCount

    private val _items = MutableStateFlow<List<Notification>>(emptyList())
    private val _unreadCount = MutableStateFlow(0)

    /**
     * Loads persisted history, restores unread state and applies retention,
     * then re-prunes the table (age + count). Failures leave the current
     * in-memory tray untouched.
     */
    suspend fun load() {
        val persisted =
            runCatching { dao.listNewestFirst() }.getOrNull() ?: return
        val now = clockMillis()
        val retained = NotificationRetention.apply(persisted.map { it.toDomain() }, now)
        _items.value = retained.takeLast(MAX_NOTIFICATIONS)
        _unreadCount.value = retained.count { it.readAt == null }
        runCatching { pruneTable(now) }
    }

    /** Persists one notification, prunes the table, and folds it into the tray view. */
    suspend fun save(notification: Notification) {
        runCatching {
            dao.upsert(notification.toEntity())
            pruneTable(clockMillis())
        }
        integrate(listOf(notification))
    }

    /** Marks one entry read; a missing or already-read id is a no-op. */
    suspend fun markRead(
        id: String,
        readAt: Long = clockMillis(),
    ) {
        runCatching { dao.markRead(id, readAt) }
        _items.update { current ->
            current.map { if (it.id == id && it.readAt == null) it.copy(readAt = readAt) else it }
        }
        recomputeUnread()
    }

    /** Marks every unread entry read. */
    suspend fun markAllRead(readAt: Long = clockMillis()) {
        runCatching { dao.markAllRead(readAt) }
        _items.update { current -> current.map { if (it.readAt == null) it.copy(readAt = readAt) else it } }
        recomputeUnread()
    }

    /** Deletes the persisted history and empties the tray. */
    suspend fun clear() {
        runCatching { dao.clear() }
        _items.value = emptyList()
        _unreadCount.value = 0
    }

    /** Applies the age + count retention bounds to the table. */
    suspend fun prune(now: Long = clockMillis()) {
        runCatching { pruneTable(now) }
    }

    private suspend fun pruneTable(now: Long) {
        dao.deleteOlderThan(now - NotificationRetention.MAX_AGE_MILLIS)
        dao.deleteBeyondCount(NotificationRetention.MAX_COUNT)
    }

    private fun integrate(incoming: List<Notification>) {
        val merged = (_items.value + incoming).distinctBy { it.id }
        val retained = NotificationRetention.apply(merged, clockMillis())
        _items.value = retained.takeLast(MAX_NOTIFICATIONS)
        recomputeUnread()
    }

    private fun recomputeUnread() {
        _unreadCount.value = _items.value.count { it.readAt == null }
    }

    companion object {
        /**
         * View cap for [items] (the tray size). Retention is the durable bound;
         * this only limits how many entries the current tray renders.
         */
        const val MAX_NOTIFICATIONS: Int = 50
    }
}

private const val TARGET_KIND_BOOK = "book"
private const val TARGET_KIND_ROUTE = "route"

/** Flattens the sealed target into `target_kind` + `target_id`. */
internal fun Notification.toEntity(): NotificationEntity =
    NotificationEntity(
        id = id,
        createdAt = createdAt,
        source = source.name,
        category = category.name,
        severity = severity.name,
        interruption = interruption.name,
        i18nKey = i18nKey,
        i18nParams = i18nParams,
        targetKind =
            when (target) {
                is NotificationTarget.Book -> TARGET_KIND_BOOK
                is NotificationTarget.Route -> TARGET_KIND_ROUTE
                null -> null
            },
        targetId =
            when (target) {
                is NotificationTarget.Book -> target.bookId
                is NotificationTarget.Route -> target.route.name
                null -> null
            },
        readAt = readAt,
        dedupKey = dedupKey,
    )

/**
 * Re-assembles the sealed target and degrades unknown persisted values to null
 * (an unreadable target must never crash the tray).
 */
internal fun NotificationEntity.toDomain(): Notification =
    Notification(
        id = id,
        createdAt = createdAt,
        source = NotificationSource.entries.firstOrNull { it.name == source } ?: NotificationSource.SYNC,
        category = Category.entries.firstOrNull { it.name == category } ?: Category.SYSTEM,
        severity = Severity.entries.firstOrNull { it.name == severity } ?: Severity.INFO,
        interruption = Interruption.entries.firstOrNull { it.name == interruption } ?: Interruption.SILENT,
        i18nKey = i18nKey,
        i18nParams = i18nParams,
        target = targetFor(targetKind, targetId),
        readAt = readAt,
        dedupKey = dedupKey,
    )

private fun targetFor(
    kind: String?,
    id: String?,
): NotificationTarget? {
    if (id == null) return null
    return when (kind) {
        TARGET_KIND_BOOK -> NotificationTarget.Book(id)
        TARGET_KIND_ROUTE ->
            NotificationRouteTarget.entries
                .firstOrNull { it.name == id }
                ?.let { NotificationTarget.Route(it) }
        else -> null
    }
}
