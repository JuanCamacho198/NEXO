package com.nexo.data.epub

import java.io.InputStream

interface EpubParserService {
    suspend fun extractMetadata(inputStream: InputStream): Result<EpubMetadata>
}
