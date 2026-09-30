package com.nexo.data.repository

import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.entity.DictionaryWordEntity
import com.nexo.domain.model.DictionaryWord
import com.nexo.domain.repository.DictionaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class DictionaryRepositoryImpl(
    private val dao: DictionaryWordDao,
) : DictionaryRepository {
    override fun observeAll(): Flow<List<DictionaryWord>> =
        dao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    override fun search(query: String): Flow<List<DictionaryWord>> =
        dao.search(query).map { entities ->
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
            val entity =
                DictionaryWordEntity(
                    id = UUID.randomUUID().toString(),
                    word = word.trim(),
                    addedAtEpochMillis = System.currentTimeMillis(),
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
            dao.updateUserFields(
                wordId = wordId,
                definition = definition.cleaned(),
                partOfSpeech = partOfSpeech.cleaned(),
                phonetic = phonetic.cleaned(),
                example = example.cleaned(),
            )
            val updated = dao.findById(wordId) ?: error("Word $wordId not found after update")
            updated.toDomain()
        }

    override suspend fun delete(wordId: String) {
        dao.delete(wordId)
    }

    /**
     * Identity is the normalized key (REQ-DSI-004). Android has no `normalized_word` column
     * (Decision 12), so the comparison runs in Kotlin over the narrow `word` projection.
     */
    override suspend fun exists(word: String): Boolean {
        val key = DictionaryNormalizer.normalize(word)
        return dao.allWords().any { DictionaryNormalizer.normalize(it) == key }
    }

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
