package com.nexo.data.repository

import com.nexo.data.local.DefinitionCacheKey
import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.dao.DefinitionCacheDao
import com.nexo.data.local.entity.DefinitionCacheEntity
import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.repository.DefinitionRepository
import com.nexo.domain.session.ActiveUserProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache-first definition lookup, scoped to the active account.
 *
 * Reads the local `definition_cache` for an exact `(owner, readerLanguage,
 * wordLanguage, word)` hit and only drives the cascade on a miss; a real result
 * is written back so the next selection is free (and offline). The owner comes
 * from [ActiveUserProvider] and is resolved at call time, never captured.
 *
 * ## The anonymous path (decision D5)
 *
 * When [ActiveUserProvider.currentUserId] is `null` the repository is
 * read-only: nothing is read (nothing could match an empty owner anyway) and
 * nothing is stored. An anonymous lookup still works — it just is not
 * remembered, and it cannot observe another account's cached lookups.
 *
 * ## What is NOT cached, and why
 *
 * - **An empty result.** "No definition" is a statement about the network that
 *   day, not about the word; pinning it would trap the word forever. Handled in
 *   [DefinitionCacheCodec.encode], which returns `null` for it — so the ODD's
 *   rule lives with the DTO it protects rather than being re-decided here.
 * - **A failure.** Same reason: the service was down, the word was not absent.
 *
 * ## The personal dictionary is untouched
 *
 * This decorator only reads and writes `definition_cache`. It never touches
 * `dictionary_words`: a lookup is read-only, and an explicit "add to my
 * dictionary" action is the only writer (ODD invariant 3).
 *
 * Room I/O runs on [ioDispatcher]; the delegate inherits it too, so a cache
 * write and a cascade miss share one off-main hop. The repository never throws
 * — [DefinitionRepository] implementations are total.
 */
class CachedDefinitionRepository(
    private val delegate: DefinitionRepository,
    private val cacheDao: DefinitionCacheDao,
    private val activeUser: ActiveUserProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : DefinitionRepository {
    override suspend fun define(request: DefinitionRequest): DefinitionResult {
        val word = request.word.trim()
        val owner = activeUser.currentUserId()
        val normalized = DictionaryNormalizer.normalize(word)
        val key =
            owner?.let {
                DefinitionCacheKey.of(
                    owner = it,
                    readerLanguage = request.readerLanguage,
                    wordLanguage = request.wordLanguage.wordLanguage,
                    wordNormalized = normalized,
                )
            }

        if (owner != null && key != null) {
            cachedOrNull(owner, key)?.let { return it }
        }

        val fresh = delegate.define(request)

        // Anonymous path writes nothing; an empty or failed result writes
        // nothing either (the codec returns null for both).
        if (owner != null && key != null) {
            store(owner, key, word, normalized, request, fresh)
        }
        return fresh
    }

    /**
     * Caps the cache for the active account at [MAX_CACHED_DEFINITIONS_PER_USER]
     * rows, oldest first. A row limit rather than a day-based cutoff, matching
     * the translation cache: a predictable ceiling on disk, not a punishment for
     * a heavy week.
     *
     * The anonymous path owns no cache, so it does nothing. Never throws.
     */
    suspend fun pruneCache(): Int =
        withContext(ioDispatcher) {
            val owner = activeUser.currentUserId() ?: return@withContext 0
            if (cacheDao.count(owner) <= MAX_CACHED_DEFINITIONS_PER_USER) return@withContext 0
            cacheDao.deleteOldestBeyond(owner, MAX_CACHED_DEFINITIONS_PER_USER)
        }

    private suspend fun cachedOrNull(
        owner: String,
        key: String,
    ): DefinitionResult? =
        withContext(ioDispatcher) {
            val row = cacheDao.get(owner, key) ?: return@withContext null
            // A corrupt payload reads as a MISS, not an error: the cascade
            // repairs it on the next lookup.
            DefinitionCacheCodec.decode(row.payload)
        }

    private suspend fun store(
        owner: String,
        key: String,
        word: String,
        normalized: String,
        request: DefinitionRequest,
        result: DefinitionResult,
    ) = withContext(ioDispatcher) {
        val payload = DefinitionCacheCodec.encode(result) ?: return@withContext
        cacheDao.put(
            DefinitionCacheEntity(
                key = key,
                userId = owner,
                word = word,
                wordNormalized = normalized,
                wordLanguage = request.wordLanguage.wordLanguage,
                readerLanguage = request.readerLanguage,
                payload = payload,
                createdAtEpochMillis = nowMillis(),
            ),
        )
    }

    companion object {
        /**
         * Per-account ceiling for the definition cache. Mirroring the
         * translation cache's 500: far below anything that shows up as disk use,
         * while leaving the common case — a reader re-selecting words within a
         * book — entirely cached.
         */
        const val MAX_CACHED_DEFINITIONS_PER_USER = 500
    }
}
