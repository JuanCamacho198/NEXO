package com.nexo.domain.model

data class Bookmark(
    val id: String,
    val bookId: String,
    val cfiLocation: String,
    val titleOrSnippet: String,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
    val locatorJson: String? = null,
)
