package com.nexo.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictionary_words")
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
)
