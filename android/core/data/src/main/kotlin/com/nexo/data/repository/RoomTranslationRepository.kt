package com.nexo.data.repository

import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.dao.TranslationCacheDao
import com.nexo.data.local.entity.TranslationCacheEntity
import com.nexo.data.remote.translation.MyMemoryTranslationClient
import com.nexo.data.remote.translation.TranslationResult
import com.nexo.domain.repository.TranslationRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.translation.CachedTranslation
import com.nexo.domain.translation.TranslationOutcome
import com.nexo.domain.translation.TranslationPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Cache-first translation, scoped to the active account.
 *
 * Reads the local `translation_cache` for an exact `(owner, lang_pair, word)`
 * hit and only calls MyMemory on a miss; a real translation is written back so
 * the next selection is free (and offline). The owner comes from
 * [ActiveUserProvider] and is resolved at call time, never captured.
 *
 * ## The anonymous path (decision D5)
 *
 * When [ActiveUserProvider.isAnonymous] the repository is read-only: the lookup
 * is skipped (nothing could match an empty owner anyway) and nothing is stored.
 * An anonymous translation still works — it just is not remembered, and it
 * cannot observe another account's cached lookups.
 *
 * Room I/O runs on [ioDispatcher]; the network call inherits the caller's
 * context. The repository never throws.
 */
class RoomTranslationRepository(
    private val client: MyMemoryTranslationClient,
    private val cacheDao: TranslationCacheDao,
    private val activeUser: ActiveUserProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : TranslationRepository {
    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        allowNetwork: Boolean,
    ): TranslationOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TranslationOutcome.Error("empty text")
        val langPair = TranslationPolicy.langPair(sourceLanguage, targetLanguage)
        val normalizedKey = DictionaryNormalizer.normalize(trimmed)
        val owner = activeUser.currentUserId()

        if (owner != null) {
            cachedOrNull(owner, langPair, normalizedKey, trimmed)?.let {
                return TranslationOutcome.Translated(text = it, fromCache = true)
            }
        }

        if (!allowNetwork) return TranslationOutcome.Offline

        return when (val result = client.translate(trimmed, sourceLanguage, targetLanguage)) {
            is TranslationResult.Success -> {
                if (owner != null) {
                    store(owner, langPair, normalizedKey, trimmed, result.translated)
                }
                TranslationOutcome.Translated(text = result.translated, fromCache = false)
            }
            TranslationResult.NotTranslated -> TranslationOutcome.NoTranslation
            TranslationResult.NetworkError -> TranslationOutcome.Offline
            is TranslationResult.Failure -> TranslationOutcome.Error(result.message)
        }
    }

    private suspend fun cachedOrNull(
        userId: String,
        langPair: String,
        normalizedKey: String,
        original: String,
    ): String? =
        withContext(ioDispatcher) {
            val row = cacheDao.get(userId, langPair, normalizedKey)
            TranslationPolicy.cachedTranslationOrNull(
                cached =
                    row?.let {
                        CachedTranslation(
                            original = it.originalText,
                            langPair = it.langPair,
                            translated = it.translatedText,
                        )
                    },
                text = original,
                langPair = langPair,
            )
        }

    private suspend fun store(
        userId: String,
        langPair: String,
        normalizedKey: String,
        original: String,
        translated: String,
    ) = withContext(ioDispatcher) {
        cacheDao.put(
            TranslationCacheEntity(
                id = UUID.randomUUID().toString(),
                userId = userId,
                originalText = original,
                wordNormalized = normalizedKey,
                langPair = langPair,
                translatedText = translated,
                createdAtEpochMillis = nowMillis(),
            ),
        )
    }

    /**
     * Caps the cache for the active account at
     * [MAX_CACHED_TRANSLATIONS_PER_USER] rows, oldest first.
     *
     * A row limit rather than a day-based cutoff (decision D2): the point is a
     * predictable ceiling on disk, and a cutoff would evict the whole history
     * of a reader who is heavy one week and idle the next. The sweep is
     * deliberately not run per translation — it runs at the session boundary
     * that `TranslationManager.onBookOpened` provides.
     *
     * The anonymous path owns no cache, so it does nothing. Never throws.
     */
    override suspend fun pruneCache(): Int =
        withContext(ioDispatcher) {
            val owner = activeUser.currentUserId() ?: return@withContext 0
            if (cacheDao.count(owner) <= MAX_CACHED_TRANSLATIONS_PER_USER) return@withContext 0
            cacheDao.deleteOldestBeyond(owner, MAX_CACHED_TRANSLATIONS_PER_USER)
        }

    companion object {
        /**
         * Per-account ceiling for the translation cache. 500 rows is roughly
         * the size of a long novel's worth of repeated lookups and stays far
         * below anything that would show up as disk use, while leaving the
         * common case — a reader re-selecting words within a book — entirely
         * cached.
         */
        const val MAX_CACHED_TRANSLATIONS_PER_USER = 500
    }
}
