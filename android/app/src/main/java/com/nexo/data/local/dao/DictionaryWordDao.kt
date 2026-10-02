package com.nexo.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.nexo.data.local.entity.DictionaryWordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryWordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(word: DictionaryWordEntity)

    @Query("SELECT * FROM dictionary_words ORDER BY addedAtEpochMillis DESC")
    fun observeAll(): Flow<List<DictionaryWordEntity>>

    @Query("SELECT * FROM dictionary_words WHERE word LIKE '%' || :query || '%' ORDER BY addedAtEpochMillis DESC")
    fun search(query: String): Flow<List<DictionaryWordEntity>>

    @Query("DELETE FROM dictionary_words WHERE id = :wordId")
    suspend fun delete(wordId: String)

    @Query("SELECT COUNT(*) FROM dictionary_words WHERE LOWER(word) = LOWER(:word)")
    suspend fun countByWord(word: String): Int

    @Query("SELECT * FROM dictionary_words WHERE id = :wordId LIMIT 1")
    suspend fun findById(wordId: String): DictionaryWordEntity?

    /**
     * Full local snapshot used by dictionary sync to resolve remote rows to
     * their local counterpart by normalized word (FR-09). The dictionary is a
     * small user-curated set; WU3 replaces this scan with the normalized index.
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

    @Query("SELECT word FROM dictionary_words")
    suspend fun allWords(): List<String>

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
