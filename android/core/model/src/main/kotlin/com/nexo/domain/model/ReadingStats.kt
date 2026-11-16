package com.nexo.domain.model

data class ReadingStats(
    val minutesRead: Int = 0,
    val sessionCount: Int = 0,
    val dailyProgressPercent: Float = 0f,
)
