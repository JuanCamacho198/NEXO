package com.nexo.data.repository

import com.nexo.data.remote.translation.MyMemoryTranslationClient
import com.nexo.data.remote.translation.TranslationResult
import com.nexo.data.remote.wiktionary.WiktionaryClient
import com.nexo.domain.definition.DefinitionCascade
import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.GlossTranslator
import com.nexo.domain.definition.WikitextSource
import com.nexo.domain.repository.DefinitionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The definition lookup, wired to the real sources.
 *
 * ## What this class is NOT
 *
 * It touches no database. In particular it never reads or writes
 * `dictionary_words`: a lookup is read-only, and the explicit "add to my
 * dictionary" action (a later task) is the only writer of the personal
 * dictionary (ODD invariant 3).
 *
 * ## The cache
 *
 * There is no cache here. The ODD places `definition_cache` in T5, AFTER this
 * cascade (T4) whose DTO it stores; adding it now would build the thing T5
 * specifies with nothing to align against. When T5 lands it brackets this
 * repository, and it is account-scoped in the repository (no active account →
 * neither read nor write), exactly as [RoomTranslationRepository] does.
 *
 * ## Failure posture
 *
 * The two Wikimedia sources each go through [WiktionaryClient], and MyMemory
 * through the already-integrated [MyMemoryTranslationClient] (step 3 — reused,
 * not reimplemented). All three are total across their boundaries, so this
 * repository never throws. Network I/O inherits the caller's context; the
 * dispatch switch exists so a future cache write can move I/O off the caller.
 */
class RemoteDefinitionRepository(
    private val wiktionaryClient: WiktionaryClient,
    private val translationClient: MyMemoryTranslationClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DefinitionRepository {
    override suspend fun define(request: DefinitionRequest): DefinitionResult =
        withContext(ioDispatcher) {
            DefinitionCascade(
                nativeSource =
                    WikitextSource { word, wiki ->
                        wiktionaryClient.fetchWikitext(word, wiki)
                    },
                englishSource =
                    WikitextSource { word, wiki ->
                        wiktionaryClient.fetchWikitext(word, wiki)
                    },
                translator =
                    GlossTranslator { text, targetLanguage ->
                        when (val result = translationClient.translate(text, ENGLISH, targetLanguage)) {
                            is TranslationResult.Success -> result.translated
                            // A failed translation is a MISS, so the cascade reports
                            // "no definition" rather than showing raw English under a
                            // translated mark.
                            else -> null
                        }
                    },
            ).define(request)
        }

    private companion object {
        /** Step 3 translates FROM English: step 2 only ever yields English glosses. */
        const val ENGLISH = "en"
    }
}
