package com.nexo.data.storage

interface CoverStorage {
    suspend fun saveCover(
        bookId: String,
        coverBytes: ByteArray,
    ): Result<String>

    suspend fun deleteCover(bookId: String): Result<Unit>
}
