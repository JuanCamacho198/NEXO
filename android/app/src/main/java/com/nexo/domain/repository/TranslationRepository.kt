package com.nexo.domain.repository

import com.nexo.domain.translation.TranslationOutcome

/**
 * Translates a reader selection, using a local cache before spending network
 * quota. Implementations must never throw: every failure is expressed as a
 * terminal [TranslationOutcome].
 */
interface TranslationRepository {
    /**
     * Translates [text] from [sourceLanguage] to [targetLanguage].
     *
     * A cached result is preferred over a network call; on a cache miss the
     * upstream is queried and, on a real translation, the result is cached for
     * next time. [allowNetwork] lets a caller force a cache-only read (e.g. to
     * avoid touching the network when offline is already known).
     */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        allowNetwork: Boolean = true,
    ): TranslationOutcome
}
