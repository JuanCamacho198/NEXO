package com.nexo.data.repository

import com.nexo.data.local.dao.DefinitionCacheDao
import com.nexo.domain.model.DefinitionHistoryEntry
import com.nexo.domain.repository.DefinitionHistoryRepository
import com.nexo.domain.session.ActiveUserProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Definition history for the settings screen, read from the definition cache.
 *
 * ## Why the cache table and not a second one
 *
 * A word the user looked up IS the thing they want to recall, and the cache
 * already persists exactly that row: the word, both languages, the serialised
 * result and the timestamp. A dedicated history table would store a
 * byte-identical copy and then have to be kept in sync with the cache on every
 * lookup — two writers for one fact.
 *
 * ## Clearing the history ALSO empties the offline cache
 *
 * They are the same table, so [clearHistory] cannot empty one without the other.
 * This is documented rather than hidden: the confirmation copy says so, and the
 * cost is honest — the words are looked up online again next time, which is
 * exactly what "forget this" has to mean for a cache-backed log. A second table
 * would let the two diverge and quietly keep a copy of what the user deleted.
 *
 * ## The retention caveat
 *
 * The cache is pruned by row count via
 * [DefinitionCacheDao.deleteOldestBeyond] (see `CachedDefinitionRepository`), so
 * that sweep prunes the history too. Acceptable for a cache-backed log, and
 * [clearHistory] is the control that actually matters.
 *
 * ## Decoding
 *
 * [DefinitionCacheCodec.decode] returns `null` for a corrupt payload. That row is
 * SKIPPED (`mapNotNull`), never thrown: one unreadable row must not blank the
 * whole list or crash the settings screen.
 *
 * ## Isolation
 *
 * Anonymous accounts have no owner (decision D5), so the flow is empty and
 * [clearHistory] is a no-op. Both guards are `?: return` expressions that run
 * BEFORE the DAO: an ownerless read that fell through would evaluate to the
 * empty string, which matches no row and therefore disappears silently — the
 * guard is only correct if the call never happens.
 *
 * @param limit the most rows the settings list will show. Clamped to at least
 *   1: SQLite reads `LIMIT -1` as "no limit", so a zero or negative value must
 *   never reach the query.
 */
class RoomDefinitionHistoryRepository(
    private val cacheDao: DefinitionCacheDao,
    private val activeUser: ActiveUserProvider,
    private val limit: Int = DEFAULT_HISTORY_LIMIT,
) : DefinitionHistoryRepository {
    override fun history(): Flow<List<DefinitionHistoryEntry>> {
        val owner = activeUser.currentUserId() ?: return flowOf(emptyList())
        return cacheDao.history(owner, limit.coerceAtLeast(1)).map { rows ->
            rows.mapNotNull { row ->
                val result = DefinitionCacheCodec.decode(row.payload) ?: return@mapNotNull null
                val first = result.definitions.firstOrNull() ?: return@mapNotNull null
                DefinitionHistoryEntry(
                    word = row.word,
                    definition = first.text,
                    partOfSpeech = first.partOfSpeech,
                    wordLanguage = row.wordLanguage,
                    readerLanguage = row.readerLanguage,
                    createdAtEpochMillis = row.createdAtEpochMillis,
                )
            }
        }
    }

    override suspend fun clearHistory() {
        val owner = activeUser.currentUserId() ?: return
        cacheDao.deleteAll(owner)
    }

    companion object {
        /**
         * Unbounded by design would decode the whole cache on every visit; a
         * cap this size fills several screens of scrolling and stays cheap.
         */
        const val DEFAULT_HISTORY_LIMIT: Int = 100
    }
}
