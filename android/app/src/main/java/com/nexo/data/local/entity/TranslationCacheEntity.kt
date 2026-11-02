package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of machine translations so a repeated selection does not spend
 * MyMemory quota again (and works offline once seen).
 *
 * Account-agnostic by design: a translation of a word does not belong to a
 * user, and the row carries no PII beyond the word the user already selected.
 * The composite [Index] on `(lang_pair, word_normalized)` serves the lookup;
 * a second index on `created_at` serves the eventual retention sweep.
 *
 * `word_normalized` mirrors the dictionary convention (trim + lowercase) so a
 * selection differing only in case resolves to the same row.
 */
@Entity(
    tableName = "translation_cache",
    indices = [
        Index(value = ["lang_pair", "word_normalized"]),
        Index(value = ["created_at"]),
    ],
)
data class TranslationCacheEntity(
    @PrimaryKey
    val id: String,
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
