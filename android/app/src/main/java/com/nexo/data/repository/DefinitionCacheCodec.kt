package com.nexo.data.repository

import com.nexo.domain.definition.DefinitionItem
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.DefinitionSource
import com.nexo.domain.definition.WIKTIONARY_ATTRIBUTION
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serialises a [DefinitionResult] into the `definition_cache` payload, and back.
 *
 * ## Why a DTO and not the domain type directly
 *
 * The domain [DefinitionResult] is the reader-facing shape and is free to gain
 * fields (a future `synonyms`, a `register`) without a schema decision. Pinning
 * the STORED shape — [CachedDefinitionPayload] — means the domain can evolve
 * and a decode of an older payload still produces a coherent result instead of
 * throwing. `ignoreUnknownKeys` makes a payload written by a NEWER build
 * readable by an older one.
 *
 * ## Fullness and totality
 *
 * [encode] never stores an empty result (the ODD: "no definition" is a statement
 * about the network that day, not about the word — caching it would trap the
 * word forever). [decode] returns `null` for a payload that does not parse: a
 * corrupt row is a MISS, not an error, and the cascade repairs it.
 *
 * The attribution is NOT stored: it is a constant the licence obliges every
 * result to carry, and reconstructing it from the code is exactly the
 * "forgettable" failure the field exists to prevent. It is re-attached on
 * decode, so a cached result renders the attribution too.
 */
object DefinitionCacheCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    /** Encodes [result] for storage, or `null` when it must not be cached. */
    fun encode(result: DefinitionResult): String? {
        // The ODD: an empty result is NEVER cached. The failure branch has no
        // definitions either, and must not be cached for the same reason —
        // "the service was down" is not a fact about the word.
        if (result.definitions.isEmpty()) return null
        val source = result.source ?: return null
        val payload =
            CachedDefinitionPayload(
                word = result.word,
                definitions =
                    result.definitions.map {
                        CachedDefinitionItem(text = it.text, partOfSpeech = it.partOfSpeech)
                    },
                source = source.name,
                wordLanguage = result.wordLanguage,
                readerLanguage = result.readerLanguage,
                wordLanguageKnown = result.wordLanguageKnown,
            )
        return json.encodeToString(CachedDefinitionPayload.serializer(), payload)
    }

    /** Decodes a stored [payload], or `null` when it is corrupt (a MISS). */
    fun decode(payload: String): DefinitionResult? {
        val parsed =
            try {
                json.decodeFromString(CachedDefinitionPayload.serializer(), payload)
            } catch (_: Exception) {
                return null
            }
        val source =
            try {
                DefinitionSource.valueOf(parsed.source)
            } catch (_: IllegalArgumentException) {
                return null
            }
        return DefinitionResult(
            word = parsed.word,
            definitions =
                parsed.definitions.map {
                    DefinitionItem(text = it.text, partOfSpeech = it.partOfSpeech)
                },
            source = source,
            // Re-attached, never trusted from storage: the licence requires the
            // attribution to accompany the reused text, and the constant is the
            // one source that cannot go stale.
            attribution = WIKTIONARY_ATTRIBUTION,
            wordLanguage = parsed.wordLanguage,
            readerLanguage = parsed.readerLanguage,
            wordLanguageKnown = parsed.wordLanguageKnown,
        )
    }
}

@Serializable
internal data class CachedDefinitionPayload(
    @SerialName("word")
    val word: String,
    @SerialName("definitions")
    val definitions: List<CachedDefinitionItem>,
    @SerialName("source")
    val source: String,
    @SerialName("word_language")
    val wordLanguage: String,
    @SerialName("reader_language")
    val readerLanguage: String,
    @SerialName("word_language_known")
    val wordLanguageKnown: Boolean,
)

@Serializable
internal data class CachedDefinitionItem(
    @SerialName("text")
    val text: String,
    @SerialName("part_of_speech")
    val partOfSpeech: String? = null,
)
