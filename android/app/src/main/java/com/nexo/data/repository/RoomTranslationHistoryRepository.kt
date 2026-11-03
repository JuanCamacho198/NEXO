package com.nexo.data.repository

import com.nexo.data.local.dao.TranslationCacheDao
import com.nexo.domain.model.TranslationHistoryEntry
import com.nexo.domain.repository.TranslationHistoryRepository
import com.nexo.domain.session.ActiveUserProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Translation history for the settings screen, read from the translation cache.
 *
 * ## Why the cache table and not a second one
 *
 * A translation the user performed IS the thing they want to re-read, and the
 * cache already persists exactly that row: original, translation, language pair
 * and timestamp. A dedicated history table would store a byte-identical copy and
 * then have to be kept in sync with the cache on every lookup — two writers for
 * one fact.
 *
 * ## The retention caveat
 *
 * The cache is pruned by row count, and that sweep is wired:
 * `TranslationManager` calls `pruneCache()` after a lookup, and
 * `RoomTranslationRepository.pruneCache()` keeps only the
 * `MAX_CACHED_TRANSLATIONS_PER_USER` newest rows for the owner via
 * `deleteOldestBeyond`. Because the history shares this table, that sweep also
 * prunes the history, which is acceptable for a cache-backed translation log and
 * is why this repository is named for the history but documented as cache-backed
 * rather than pretending otherwise. (An earlier age-based sweep,
 * `TranslationCacheDao.deleteOlderThan`, was never wired and has been removed —
 * one retention policy, not two.) A user who wants the log gone NOW has
 * [clearHistory], which is the privacy control that actually matters.
 *
 * Anonymous accounts have no owner, so the flow is empty and [clearHistory] is a
 * no-op: the same decision D5 rule the rest of the app follows.
 */
class RoomTranslationHistoryRepository(
    private val cacheDao: TranslationCacheDao,
    private val activeUser: ActiveUserProvider,
) : TranslationHistoryRepository {
    override fun history(): Flow<List<TranslationHistoryEntry>> {
        val owner = activeUser.currentUserId() ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return cacheDao.history(owner).map { rows ->
            rows.map {
                TranslationHistoryEntry(
                    id = it.id,
                    original = it.originalText,
                    translated = it.translatedText,
                    langPair = it.langPair,
                    createdAtEpochMillis = it.createdAtEpochMillis,
                )
            }
        }
    }

    override suspend fun clearHistory() {
        val owner = activeUser.currentUserId() ?: return
        cacheDao.deleteAll(owner)
    }
}
