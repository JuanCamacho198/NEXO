package com.nexo.domain.model

data class BookImportRequest(
    val sourcePath: String,
    val fallbackTitle: String? = null,
)
