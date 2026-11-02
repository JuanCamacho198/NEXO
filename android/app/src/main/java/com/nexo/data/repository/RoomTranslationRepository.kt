package com.nexo.data.repository

import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.dao.TranslationCacheDao
import com.nexo.data.local.entity.TranslationCacheEntity
import com.nexo.data.remote.translation.MyMemoryTranslationClient
import com.nexo.data.remote.translation.TranslationResult
import com.nexo.domain.repository.TranslationRepository
import com.nexo.domain.translation.CachedTranslation
import com.nexo.domain.translation.TranslationOutcome
import com.nexo.domain.translation.TranslationPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Cache-first translation. Reads the local `translation_cache` for an exact
 * `(lang_pair, word)` hit and only calls MyMemory on a miss; a real
 * translation is written back so the next selection is free (and offline).
 *
 * Room I/O runs on [ioDispatcher]; the network call inherits the caller's
 * context. The repository never throws.
 */
class RoomTranslationRepository(
    private val client: MyMemoryTranslationClient,
    private val cacheDao: TranslationCacheDao,
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

        cachedOrNull(langPair, normalizedKey, trimmed)?.let {
            return TranslationOutcome.Translated(text = it, fromCache = true)
        }

        if (!allowNetwork) return TranslationOutcome.Offline

        return when (val result = client.translate(trimmed, sourceLanguage, targetLanguage)) {
            is TranslationResult.Success -> {
                store(langPair, normalizedKey, trimmed, result.translated)
                TranslationOutcome.Translated(text = result.translated, fromCache = false)
            }
            TranslationResult.NotTranslated -> TranslationOutcome.NoTranslation
            TranslationResult.NetworkError -> TranslationOutcome.Offline
            is TranslationResult.Failure -> TranslationOutcome.Error(result.message)
        }
    }

    private suspend fun cachedOrNull(
        langPair: String,
        normalizedKey: String,
        original: String,
    ): String? =
        withContext(ioDispatcher) {
            val row = cacheDao.get(langPair, normalizedKey)
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
        langPair: String,
        normalizedKey: String,
        original: String,
        translated: String,
    ) = withContext(ioDispatcher) {
        cacheDao.put(
            TranslationCacheEntity(
                id = UUID.randomUUID().toString(),
                originalText = original,
                wordNormalized = normalizedKey,
                langPair = langPair,
                translatedText = translated,
                createdAtEpochMillis = nowMillis(),
            ),
        )
    }
}
