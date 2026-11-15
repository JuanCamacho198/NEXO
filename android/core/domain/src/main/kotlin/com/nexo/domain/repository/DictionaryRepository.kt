package com.nexo.domain.repository

import com.nexo.domain.model.DictionaryWord
import kotlinx.coroutines.flow.Flow

interface DictionaryRepository {
    fun observeAll(userId: String): Flow<List<DictionaryWord>>

    fun search(
        query: String,
        userId: String,
    ): Flow<List<DictionaryWord>>

    /**
     * Creates the full row. The four user-authored fields and the six evidence fields are all
     * optional; Android's reader path passes none of them (there is no Android capture path).
     */
    suspend fun save(
        word: String,
        userId: String,
        definition: String? = null,
        partOfSpeech: String? = null,
        phonetic: String? = null,
        example: String? = null,
        quote: String? = null,
        sourceBookId: String? = null,
        sourceBookTitle: String? = null,
        sourceBookAuthor: String? = null,
        sourceChapter: String? = null,
        sourceLocator: String? = null,
    ): Result<DictionaryWord>

    /** Rewrites the four user-authored fields; the six evidence columns are not addressable here. */
    suspend fun updateUserFields(
        wordId: String,
        userId: String,
        definition: String?,
        partOfSpeech: String?,
        phonetic: String?,
        example: String?,
    ): Result<DictionaryWord>

    suspend fun delete(
        wordId: String,
        userId: String,
    )

    suspend fun exists(
        word: String,
        userId: String,
    ): Boolean
}
