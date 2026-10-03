package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.entity.DictionaryWordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryWordDao {
    /**
     * Raw insert. Use [insert] for every write path: it stamps the indexed
     * `word_normalized` key from the shared normalizer so `exists()` and
     * `search()` stay consistent with the stored row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRaw(word: DictionaryWordEntity)

    suspend fun insert(word: DictionaryWordEntity) = insertRaw(word.copy(wordNormalized = DictionaryNormalizer.normalize(word.word)))

    @Query("SELECT * FROM dictionary_words ORDER BY addedAtEpochMillis DESC")
    fun observeAll(): Flow<List<DictionaryWordEntity>>

    /**
     * FR-11: prefix lookup served by `index_dictionary_words_word_normalized`.
     * The upper bound `char(1114111)` (U+10FFFF) makes the range cover every
     * string sharing the prefix, which a BINARY index can seek — unlike the old
     * non-sargable `LIKE '%query%'` scan.
     */
    @Query(
        "SELECT * FROM dictionary_words WHERE word_normalized >= :normalizedQuery " +
            "AND word_normalized < :normalizedQuery || char(1114111) " +
            "ORDER BY addedAtEpochMillis DESC",
    )
    fun search(normalizedQuery: String): Flow<List<DictionaryWordEntity>>

    @Query("DELETE FROM dictionary_words WHERE id = :wordId")
    suspend fun delete(wordId: String)

    /** FR-11: normalized equality lookup served by the same index as [search]. */
    @Query("SELECT EXISTS(SELECT 1 FROM dictionary_words WHERE word_normalized = :normalizedWord LIMIT 1)")
    suspend fun existsNormalized(normalizedWord: String): Boolean

    @Query("SELECT * FROM dictionary_words WHERE id = :wordId LIMIT 1")
    suspend fun findById(wordId: String): DictionaryWordEntity?

    /**
     * Full local snapshot used by dictionary sync to resolve remote rows to
     * their local counterpart by normalized word (FR-09). The dictionary is a
     * small user-curated set; identity is the indexed `word_normalized` column.
     */
    @Query("SELECT * FROM dictionary_words")
    suspend fun getAll(): List<DictionaryWordEntity>

    @Query(
        "UPDATE dictionary_words SET definition = :definition, part_of_speech = :partOfSpeech, " +
            "phonetic = :phonetic, example = :example, " +
            "updated_at_epoch_millis = :updatedAtEpochMillis WHERE id = :wordId",
    )
    suspend fun updateUserFields(
        wordId: String,
        definition: String?,
        partOfSpeech: String?,
        phonetic: String?,
        example: String?,
        updatedAtEpochMillis: Long,
    )

    @RawQuery(observedEntities = [DictionaryWordEntity::class])
    suspend fun searchFtsRaw(query: SupportSQLiteQuery): List<DictionaryWordEntity>

    /** Search dictionary words using FTS5 MATCH via prepared query. */
    suspend fun searchFts(query: String): List<DictionaryWordEntity> =
        searchFtsRaw(
            SimpleSQLiteQuery(
                "SELECT * FROM dictionary_words WHERE rowid IN (SELECT rowid FROM dictionary_words_fts WHERE dictionary_words_fts MATCH ?)",
                arrayOf(query),
            ),
        )
}
