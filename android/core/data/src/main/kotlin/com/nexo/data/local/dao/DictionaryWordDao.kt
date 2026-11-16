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

    @Query("SELECT * FROM dictionary_words WHERE user_id = :userId ORDER BY addedAtEpochMillis DESC")
    fun observeAll(userId: String): Flow<List<DictionaryWordEntity>>

    /**
     * FR-11: prefix lookup served by `index_dictionary_words_word_normalized`.
     * The upper bound `char(1114111)` (U+10FFFF) makes the range cover every
     * string sharing the prefix, which a BINARY index can seek — unlike the old
     * non-sargable `LIKE '%query%'` scan.
     *
     * The `user_id` predicate is an additional filter only: the range must stay
     * on `word_normalized` so SQLite keeps seeking that index. A composite index
     * leading with `user_id` was measured to fall back to a full table scan on
     * this range query.
     */
    @Query(
        "SELECT * FROM dictionary_words WHERE word_normalized >= :normalizedQuery " +
            "AND word_normalized < :normalizedQuery || char(1114111) " +
            "AND user_id = :userId " +
            "ORDER BY addedAtEpochMillis DESC",
    )
    fun search(
        normalizedQuery: String,
        userId: String,
    ): Flow<List<DictionaryWordEntity>>

    @Query("DELETE FROM dictionary_words WHERE id = :wordId AND user_id = :userId")
    suspend fun delete(
        wordId: String,
        userId: String,
    )

    /** FR-11: normalized equality lookup served by the same index as [search]. */
    @Query("SELECT EXISTS(SELECT 1 FROM dictionary_words WHERE word_normalized = :normalizedWord AND user_id = :userId LIMIT 1)")
    suspend fun existsNormalized(
        normalizedWord: String,
        userId: String,
    ): Boolean

    @Query("SELECT * FROM dictionary_words WHERE id = :wordId AND user_id = :userId LIMIT 1")
    suspend fun findById(
        wordId: String,
        userId: String,
    ): DictionaryWordEntity?

    /**
     * Full local snapshot used by dictionary sync to resolve remote rows to
     * their local counterpart by normalized word (FR-09). The dictionary is a
     * small user-curated set; identity is the indexed `word_normalized` column.
     */
    @Query("SELECT * FROM dictionary_words WHERE user_id = :userId")
    suspend fun getAll(userId: String): List<DictionaryWordEntity>

    @Query(
        "UPDATE dictionary_words SET definition = :definition, part_of_speech = :partOfSpeech, " +
            "phonetic = :phonetic, example = :example, " +
            "updated_at_epoch_millis = :updatedAtEpochMillis WHERE id = :wordId AND user_id = :userId",
    )
    suspend fun updateUserFields(
        wordId: String,
        userId: String,
        definition: String?,
        partOfSpeech: String?,
        phonetic: String?,
        example: String?,
        updatedAtEpochMillis: Long,
    )

    @RawQuery(observedEntities = [DictionaryWordEntity::class])
    suspend fun searchFtsRaw(query: SupportSQLiteQuery): List<DictionaryWordEntity>

    /** Search dictionary words using FTS5 MATCH via prepared query, scoped to [userId]. */
    suspend fun searchFts(
        query: String,
        userId: String,
    ): List<DictionaryWordEntity> =
        searchFtsRaw(
            SimpleSQLiteQuery(
                "SELECT * FROM dictionary_words WHERE user_id = ? AND rowid IN (SELECT rowid FROM dictionary_words_fts WHERE dictionary_words_fts MATCH ?)",
                arrayOf(userId, query),
            ),
        )
}
