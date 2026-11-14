package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local cache of definition lookups so a repeated selection does not spend
 * three network requests again (and works offline once seen).
 *
 * ## The key: two languages, both required
 *
 * The cascade resolves differently per READER language (`casa` on an `es` UI and
 * on an `en` UI are two different results from two different code paths) AND
 * per WORD language (the `{{lengua|XX}}` section it reads). Collapsing either
 * into a single "language" column would hand a Spanish definition back for a
 * request that asked in English. [key] is therefore the SHA-256 of
 * `owner|readerLanguage|wordLanguage|wordNormalized`, mirroring desktop's
 * `SHA-256(owner|reader|word|word)` key shape with the word language added
 * because Android's cascade takes it as an input.
 *
 * [key] is the primary key, but the composite [Index] still leads with
 * `user_id` so a lookup and the retention sweep can never cross the owner
 * boundary.
 *
 * ## Account-scoped, like every other owned table
 *
 * `user_id` is NOT NULL and defaults to the empty string, which matches
 * nothing: the anonymous path (decision D5) does not persist, so it never
 * produces a row here and an empty owner cannot leak into a real account's
 * lookups. `word_normalized` mirrors the dictionary convention (trim +
 * lowercase + NFD strip) so a selection differing only in case or accents
 * resolves to the same row.
 *
 * ## The payload is the serialised result, not a column
 *
 * [payload] holds the JSON of the cached `DefinitionResult`. The result is a
 * small tree (definitions with their POS, the source mark and the attribution),
 * and a column per sub-field would need a second table for the definitions and
 * a migration every time the DTO grows. The ODD bounds the payload at 64 KB.
 * A payload that fails to decode reads as a MISS, not an error.
 */
@Entity(
    tableName = "definition_cache",
    indices = [
        Index(value = ["user_id", "key"], unique = true),
        Index(value = ["user_id", "created_at"]),
    ],
)
data class DefinitionCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "word")
    val word: String,
    @ColumnInfo(name = "word_normalized")
    val wordNormalized: String,
    @ColumnInfo(name = "word_language")
    val wordLanguage: String,
    @ColumnInfo(name = "reader_language")
    val readerLanguage: String,
    @ColumnInfo(name = "payload")
    val payload: String,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long,
)
