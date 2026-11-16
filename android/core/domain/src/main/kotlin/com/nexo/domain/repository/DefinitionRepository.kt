package com.nexo.domain.repository

import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionResult

/**
 * Looks up the meaning of a selected word.
 *
 * The lookup is READ-ONLY with respect to the personal dictionary: it never
 * reads and never writes `dictionary_words`. Only an explicit "add to my
 * dictionary" action writes that table (ODD invariant 3).
 *
 * Implementations must never throw: every failure is expressed inside the
 * returned [DefinitionResult], whose `failure` field is distinct from an empty
 * result (ODD invariant 2).
 */
interface DefinitionRepository {
    /**
     * Resolves a definition for [DefinitionRequest.word] in
     * [DefinitionRequest.readerLanguage], for a word written in
     * [DefinitionRequest.wordLanguage].
     */
    suspend fun define(request: DefinitionRequest): DefinitionResult
}
