package com.nexo.data.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppInternalCoverStorage(
    context: Context,
) : CoverStorage {
    private val coversDir = File(context.filesDir, COVERS_DIR_NAME)

    override suspend fun saveCover(
        bookId: String,
        coverBytes: ByteArray,
    ): Result<String> =
        runCatching {
            withContext(Dispatchers.IO) {
                if (!coversDir.exists()) {
                    coversDir.mkdirs()
                }

                val coverFile = File(coversDir, "$bookId.jpg")
                coverFile.writeBytes(coverBytes)
                coverFile.absolutePath
            }
        }

    override suspend fun deleteCover(bookId: String): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                val coverFile = File(coversDir, "$bookId.jpg")
                if (coverFile.exists()) {
                    coverFile.delete()
                }
            }
        }

    private companion object {
        const val COVERS_DIR_NAME = "covers"
    }
}
