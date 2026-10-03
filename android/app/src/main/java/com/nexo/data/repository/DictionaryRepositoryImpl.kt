package com.nexo.data.repository

import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.entity.DictionaryWordEntity
import com.nexo.data.sync.DictionarySyncService
import com.nexo.domain.model.DictionaryWord
import com.nexo.domain.repository.DictionaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * [sync] is an optional best-effort cross-device mirror (FR-09). When present,
 * every local mutation is pushed to `user_dictionary_words`; the push never
 * throws, so an offline save still succeeds locally. `null` keeps legacy
 * local-only callers and tests working.
 */
class DictionaryRepositoryImpl(
    private val dao: DictionaryWordDao,
    private val sync: DictionarySyncService? = null,
) : DictionaryRepository {
    override fun observeAll(): Flow<List<DictionaryWord>> =
        dao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override fun search(query: String): Flow<List<DictionaryWord>> =
        dao.search(DictionaryNormalizer.normalize(query)).map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun save(
        word: String,
        definition: String?,
        partOfSpeech: String?,
        phonetic: String?,
        example: String?,
        quote: String?,
        sourceBookId: String?,
        sourceBookTitle: String?,
        sourceBookAuthor: String?,
        sourceChapter: String?,
        sourceLocator: String?,
    ): Result<DictionaryWord> =
        runCatching {
            val now = System.currentTimeMillis()
            val entity =
                DictionaryWordEntity(
                    id = UUID.randomUUID().toString(),
                    word = word.trim(),
                    addedAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                    definition = definition.cleaned(),
                    partOfSpeech = partOfSpeech.cleaned(),
                    phonetic = phonetic.cleaned(),
                    example = example.cleaned(),
                    quote = quote.cleaned(),
                    sourceBookId = sourceBookId.cleaned(),
                    sourceBookTitle = sourceBookTitle.cleaned(),
                    sourceBookAuthor = sourceBookAuthor.cleaned(),
                    sourceChapter = sourceChapter.cleaned(),
                    sourceLocator = sourceLocator.cleaned(),
                )
            dao.insert(entity)
            sync?.pushWord(entity)
            entity.toDomain()
        }

    override suspend fun updateUserFields(
        wordId: String,
        definition: String?,
        partOfSpeech: String?,
        phonetic: String?,
        example: String?,
    ): Result<DictionaryWord> =
        runCatching {
            val now = System.currentTimeMillis()
            dao.updateUserFields(
                wordId = wordId,
                definition = definition.cleaned(),
                partOfSpeech = partOfSpeech.cleaned(),
                phonetic = phonetic.cleaned(),
                example = example.cleaned(),
                updatedAtEpochMillis = now,
            )
            val updated = dao.findById(wordId) ?: error("Word $wordId not found after update")
            // The edit advances the local LWW write clock, so a later pull of an
            // older remote row cannot win the merge and discard this change.
            sync?.pushWord(updated, now)
            updated.toDomain()
        }

    override suspend fun delete(wordId: String) {
        val word = dao.findById(wordId)?.word
        dao.delete(wordId)
        if (word != null) sync?.deleteWord(word)
    }

    /**
     * Identity is the normalized key (REQ-DSI-004). FR-11: the lookup is an
     * indexed equality on `dictionary_words.word_normalized`; the previous
     * in-memory scan over every stored word is gone.
     */
    override suspend fun exists(word: String): Boolean = dao.existsNormalized(DictionaryNormalizer.normalize(word))

    private fun String?.cleaned(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun DictionaryWordEntity.toDomain() =
        DictionaryWord(
            id = id,
            word = word,
            addedAtEpochMillis = addedAtEpochMillis,
            definition = definition,
            partOfSpeech = partOfSpeech,
            phonetic = phonetic,
            example = example,
            quote = quote,
            sourceBookId = sourceBookId,
            sourceBookTitle = sourceBookTitle,
            sourceBookAuthor = sourceBookAuthor,
            sourceChapter = sourceChapter,
            sourceLocator = sourceLocator,
        )
}
