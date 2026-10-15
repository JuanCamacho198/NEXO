package com.nexo.domain.notification

/**
 * Pure, testable formatting of a tray timestamp relative to [nowMs].
 *
 * Kept in the domain (not the UI) so the tray's time labels are unit-testable
 * on the JVM without Compose or a Locale. Deliberately coarse — a notification
 * tray cares about "now" vs "recently" vs "older", not exact seconds.
 */
object NotificationTimestamp {
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L

    /** Which bucket a timestamp falls into, so the UI picks the string. */
    enum class Bucket { JUST_NOW, MINUTES, HOURS, DAYS }

    data class Relative(
        val bucket: Bucket,
        /** Magnitude within the bucket (minutes, hours or days); 0 for JUST_NOW. */
        val amount: Long,
    )

    fun relative(
        createdAt: Long,
        nowMs: Long,
    ): Relative {
        val elapsed = (nowMs - createdAt).coerceAtLeast(0L)
        val minutes = elapsed / MILLIS_PER_MINUTE
        return when {
            minutes < 1L -> Relative(Bucket.JUST_NOW, 0L)
            minutes < MINUTES_PER_HOUR -> Relative(Bucket.MINUTES, minutes)
            minutes < MINUTES_PER_HOUR * HOURS_PER_DAY -> Relative(Bucket.HOURS, minutes / MINUTES_PER_HOUR)
            else -> Relative(Bucket.DAYS, minutes / (MINUTES_PER_HOUR * HOURS_PER_DAY))
        }
    }
}
