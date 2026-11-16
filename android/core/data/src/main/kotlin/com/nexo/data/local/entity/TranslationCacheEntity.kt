package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of machine translations so a repeated selection does not spend
 * MyMemory quota again (and works offline once seen).
 *
 * ## Account-scoped (T3 of `translation-preferences`)
 *
 * This table used to be global on the argument that "a translation of a word
 * does not belong to a user". The *result* does not, but the ROW records what
 * was looked up, and that is reading history: translating in account A and
 * signing into account B used to hand B the A translation for the same word.
 * Every other owned table in this database carries `user_id`; the cache now does
 * too, and the composite [Index] leads with it so a lookup can never cross the
 * owner boundary.
 *
 * `user_id` is NOT NULL and defaults to the empty string, which matches nothing:
 * the anonymous path (decision D5) does not persist, so it never produces a row
 * here, and an empty owner cannot leak into a real account's lookups.
 *
 * `word_normalized` mirrors the dictionary convention (trim + lowercase) so a
 * selection differing only in case resolves to the same row.
 */
@Entity(
    tableName = "translation_cache",
    indices = [
        Index(value = ["user_id", "lang_pair", "word_normalized"]),
        Index(value = ["user_id", "created_at"]),
    ],
)
data class TranslationCacheEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "original_text")
    val originalText: String,
    @ColumnInfo(name = "word_normalized")
    val wordNormalized: String,
    @ColumnInfo(name = "lang_pair")
    val langPair: String,
    @ColumnInfo(name = "translated_text")
    val translatedText: String,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long,
)
