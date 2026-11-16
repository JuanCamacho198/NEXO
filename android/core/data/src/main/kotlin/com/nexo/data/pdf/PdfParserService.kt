package com.nexo.data.pdf

import java.io.File

interface PdfParserService {
    suspend fun extractMetadata(file: File): Result<PdfMetadata>

    fun getPageCount(file: File): Int
}
