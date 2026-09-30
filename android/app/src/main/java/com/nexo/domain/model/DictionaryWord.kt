package com.nexo.domain.model

data class DictionaryWord(
    val id: String,
    val word: String,
    val addedAtEpochMillis: Long,
    val definition: String? = null,
    val partOfSpeech: String? = null,
    val phonetic: String? = null,
    val example: String? = null,
    val quote: String? = null,
    val sourceBookId: String? = null,
    val sourceBookTitle: String? = null,
    val sourceBookAuthor: String? = null,
    val sourceChapter: String? = null,
    val sourceLocator: String? = null,
)
