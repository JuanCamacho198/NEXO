package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dictionary_words",
    indices = [Index(value = ["word_normalized"])],
)
data class DictionaryWordEntity(
    @PrimaryKey
    val id: String,
    val word: String,
    val addedAtEpochMillis: Long,
    @ColumnInfo(name = "definition")
    val definition: String? = null,
    @ColumnInfo(name = "part_of_speech")
    val partOfSpeech: String? = null,
    @ColumnInfo(name = "phonetic")
    val phonetic: String? = null,
    @ColumnInfo(name = "example")
    val example: String? = null,
    @ColumnInfo(name = "quote")
    val quote: String? = null,
    @ColumnInfo(name = "source_book_id")
    val sourceBookId: String? = null,
    @ColumnInfo(name = "source_book_title")
    val sourceBookTitle: String? = null,
    @ColumnInfo(name = "source_book_author")
    val sourceBookAuthor: String? = null,
    @ColumnInfo(name = "source_chapter")
    val sourceChapter: String? = null,
    @ColumnInfo(name = "source_locator")
    val sourceLocator: String? = null,
    /**
     * Local LWW write clock (FR-09). Distinct from [addedAtEpochMillis], which
     * means "when the word was first added" and drives list ordering/date
     * display. An edit advances this value; dictionary sync compares the remote
     * `updated_at` against it so an older remote row can never clobber a newer
     * local edit.
     */
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long = 0L,
    /**
     * Indexed natural key (FR-11): the shared [com.nexo.data.local.DictionaryNormalizer]
     * form of [word] (trim, lowercase, NFD, strip combining marks). It backs the
     * `exists()` equality lookup and the prefix search without touching [word].
     * Written through `DictionaryWordDao.insert`, which normalizes on the way in;
     * pre-existing rows are backfilled by the v30→v31 migration.
     */
    @ColumnInfo(name = "word_normalized")
    val wordNormalized: String = "",
)
