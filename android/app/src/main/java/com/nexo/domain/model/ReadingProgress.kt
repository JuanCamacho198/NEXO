package com.nexo.domain.model

data class ReadingProgress(
    val id: String,
    val bookId: String,
    val cfiLocation: String,
    val percentage: Float,
    val currentPage: Int? = null,
    val updatedAtEpochMillis: Long,
    val locatorJson: String? = null,
)
