package com.nexo.domain.model

/**
 * One row of the translation history, as the settings screen renders it. A flat
 * domain read model so the UI never sees a Room entity.
 */
data class TranslationHistoryEntry(
    val id: String,
    val original: String,
    val translated: String,
    val langPair: String,
    val createdAtEpochMillis: Long,
)
