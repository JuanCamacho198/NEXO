package com.nexo.domain.model

/**
 * Reading activity for a single day.
 */
data class DailyReadingActivity(
    val dateEpochMillis: Long,
    val minutesRead: Int,
)

/**
 * Aggregated reading statistics for the user.
 */
data class Statistics(
    val totalMinutesRead: Long = 0L,
    val currentStreak: Int = 0,
    val booksRead: Int = 0,
    val weeklyActivity: List<DailyReadingActivity> = emptyList(),
    val goalProgress: Float = 0f,
    val favoriteGenres: List<String> = emptyList(),
)
