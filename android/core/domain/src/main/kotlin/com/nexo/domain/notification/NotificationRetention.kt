package com.nexo.domain.notification

/**
 * Durable notification-retention policy (NOTIF-02).
 *
 * The tray is bounded both in memory and on disk. Both bounds are pinned here so
 * the store, the repository and their tests share one definition.
 *
 * Why these values:
 * - [MAX_COUNT] = 200: far more than a heavy week of events, only a few tens of
 *   KB on disk.
 * - [MAX_AGE_MILLIS] = 30 days: matches the app-wide tombstone window
 *   (`RetentionPruner.DEFAULT_THRESHOLD_MILLIS`), so there is one retention
 *   story in the app.
 */
object NotificationRetention {
    const val MAX_COUNT: Int = 200

    const val MAX_AGE_DAYS: Long = 30

    private const val HOURS_PER_DAY: Long = 24
    private const val MINUTES_PER_HOUR: Long = 60
    private const val SECONDS_PER_MINUTE: Long = 60
    private const val MILLIS_PER_SECOND: Long = 1000

    const val MAX_AGE_MILLIS: Long =
        MAX_AGE_DAYS * HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND

    /**
     * Applies both bounds to any list (any input order) and returns a
     * chronological (oldest-first) list whose newest entries are kept.
     */
    fun apply(
        items: List<Notification>,
        now: Long,
    ): List<Notification> {
        val cutoff = now - MAX_AGE_MILLIS
        val kept = items.filter { it.createdAt >= cutoff }.sortedBy { it.createdAt }
        if (kept.size <= MAX_COUNT) return kept
        return kept.subList(kept.size - MAX_COUNT, kept.size)
    }
}
