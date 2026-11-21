package com.nexo.domain.repository

import com.nexo.domain.model.DefinitionHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Read model for the definition history shown in settings.
 *
 * Backed by the definition cache (see
 * [com.nexo.data.repository.RoomDefinitionHistoryRepository] for why there is no
 * separate table). Implementations must never throw: a payload that fails to
 * decode is skipped, and an owner-less account reads an empty list.
 */
interface DefinitionHistoryRepository {
    /** The active account's lookups, newest first, at most the implementation's limit. */
    fun history(): Flow<List<DefinitionHistoryEntry>>

    /**
     * Forgets every definition this account looked up.
     *
     * The same table IS the offline cache, so this also empties it: a word
     * cleared here is looked up online again next time. No-op without an owner.
     */
    suspend fun clearHistory()
}
