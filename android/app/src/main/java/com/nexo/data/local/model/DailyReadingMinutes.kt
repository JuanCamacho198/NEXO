package com.nexo.data.local.model

import androidx.room.ColumnInfo

/**
 * Aggregated reading minutes for a single day.
 */
data class DailyReadingMinutes(
    @ColumnInfo(name = "date")
    val dateEpochMillis: Long,
    @ColumnInfo(name = "total_minutes")
    val totalMinutes: Int,
)
