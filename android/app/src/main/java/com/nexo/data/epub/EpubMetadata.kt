package com.nexo.data.epub

data class EpubMetadata(
    val title: String,
    val author: String?,
    val description: String? = null,
    val chapterCount: Int = 0,
    val estimatedPageCount: Int? = null,
    val coverImageBytes: ByteArray?,
    /** Language code (e.g. ISO 639-1) from `dc:language`; null when absent. */
    val language: String? = null,
    /** Publisher name from `dc:publisher`; null when absent. */
    val publisher: String? = null,
    /** Subject values from `dc:subject` (sanitized: commas stripped, trimmed, deduped). */
    val tags: List<String> = emptyList(),
    /** Publication date (ISO) from `dc:date`; null when absent. */
    val publishedDate: String? = null,
)
