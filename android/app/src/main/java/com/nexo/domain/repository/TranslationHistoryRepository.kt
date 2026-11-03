package com.nexo.domain.repository

import com.nexo.domain.model.TranslationHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Read model for the translation history shown in settings.
 *
 * Backed by the translation cache (see
 * [com.nexo.data.repository.RoomTranslationHistoryRepository] for why there is no
 * separate table). Implementations must never throw; an owner-less account reads
 * an empty list.
 */
interface TranslationHistoryRepository {
    /** The active account's translations, newest first. */
    fun history(): Flow<List<TranslationHistoryEntry>>

    /** Forgets every translation this account looked up. No-op without an owner. */
    suspend fun clearHistory()
}
