package com.nexo.presentation.feature.home

import androidx.annotation.StringRes
import com.nexo.R

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/**
 * Coarse "last read" bucket for a progress timestamp.
 *
 * Granularity is deliberately coarse: no seconds and no exact minutes, so the
 * label never needs to tick. Sub-hour reads collapse into [Kind.JustNow].
 */
sealed interface LastReadLabel {
    enum class Kind { JustNow, HoursAgo, Yesterday, DaysAgo }

    data object JustNow : LastReadLabel

    data class HoursAgo(
        val hours: Int,
    ) : LastReadLabel

    data object Yesterday : LastReadLabel

    data class DaysAgo(
        val days: Int,
    ) : LastReadLabel

    /** Null when there is nothing to show — no progress timestamp at all. */
    companion object {
        fun from(
            updatedAtEpochMillis: Long?,
            nowEpochMillis: Long,
        ): LastReadLabel? {
            if (updatedAtEpochMillis == null) return null
            // Clock skew: a future timestamp is treated as "just now", never a negative count.
            val elapsed = (nowEpochMillis - updatedAtEpochMillis).coerceAtLeast(0L)
            return when {
                elapsed < HOUR_MILLIS -> JustNow
                elapsed < DAY_MILLIS -> HoursAgo((elapsed / HOUR_MILLIS).toInt())
                elapsed < 2 * DAY_MILLIS -> Yesterday
                else -> DaysAgo((elapsed / DAY_MILLIS).toInt())
            }
        }
    }
}

@StringRes
fun LastReadLabel.stringRes(): Int =
    when (this) {
        is LastReadLabel.JustNow -> R.string.home_last_read_just_now
        is LastReadLabel.HoursAgo -> R.string.home_last_read_hours_ago
        is LastReadLabel.Yesterday -> R.string.home_last_read_yesterday
        is LastReadLabel.DaysAgo -> R.string.home_last_read_days_ago
    }

/** Format arg for [stringRes], or null for the arg-less buckets. */
fun LastReadLabel.formatArg(): Int? =
    when (this) {
        is LastReadLabel.HoursAgo -> hours
        is LastReadLabel.DaysAgo -> days
        else -> null
    }
