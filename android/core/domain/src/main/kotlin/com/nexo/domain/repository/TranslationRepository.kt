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

    /**
     * Bounds the on-disk translation cache for the active account, deleting the
     * oldest rows once it exceeds the implementation's per-account cap.
     *
     * Called at a **session boundary** — when the reader opens a book (see
     * `TranslationManager.onBookOpened`) — never per translation: a sweep on
     * every request would add a write to the hot path to save nothing.
     *
     * Returns the number of rows deleted, so a caller or a test can observe
     * that the sweep ran. Scoped to the active account; an anonymous session
     * owns no cache and is a no-op. Never throws.
     */
    suspend fun pruneCache(): Int = 0
}
