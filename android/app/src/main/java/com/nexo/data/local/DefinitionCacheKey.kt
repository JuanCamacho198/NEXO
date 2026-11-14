package com.nexo.data.local

import java.security.MessageDigest

/**
 * The `definition_cache` identity, as a pure function (ODD T5).
 *
 * The key is `SHA-256(owner|readerLanguage|wordLanguage|wordNormalized)`. Every
 * component is load-bearing:
 *
 * - **owner** — the cache is account-scoped; one account's lookups must not
 *   satisfy another's.
 * - **readerLanguage** — the cascade resolves differently per reader language:
 *   the step-1 wiki, the step-2 wiki and the step-3 MyMemory target all depend
 *   on it, so `casa` on an `es` UI and on an `en` UI are two different answers.
 * - **wordLanguage** — the `{{lengua|XX}}` section the parser reads. A word
 *   language change is a different definition of a different word.
 * - **wordNormalized** — the shared [DictionaryNormalizer] form, so a selection
 *   differing only in case or accents hits the same row.
 *
 * The word is normalized but NOT lowercased into the hash without the
 * normalizer: `DictionaryNormalizer.normalize` already trims, lowercases and
 * strips combining marks, which is the same natural key the personal dictionary
 * uses.
 *
 * This mirrors desktop's `SHA-256(owner|reader|word|word)` shape, with the word
 * language added because Android's cascade takes it as an explicit input.
 */
object DefinitionCacheKey {
    fun of(
        owner: String,
        readerLanguage: String,
        wordLanguage: String,
        wordNormalized: String,
    ): String {
        val raw = "$owner|$readerLanguage|$wordLanguage|$wordNormalized"
        val digest = MessageDigest.getInstance(SHA_256).digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private const val SHA_256 = "SHA-256"
}
