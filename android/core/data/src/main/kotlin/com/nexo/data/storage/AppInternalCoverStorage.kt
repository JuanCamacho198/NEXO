package com.nexo.data.storage

import android.content.Context
import com.nexo.domain.session.ActiveUserProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover storage scoped to the active account.
 *
 * Covers land in `covers/{userId}/{bookId}.jpg` (T8 of
 * `aislamiento-datos-por-cuenta`). Before the per-account tree, two accounts on
 * the same device resolved the same `covers/{bookId}.jpg`, so the second
 * account's cover overwrote the first's.
 *
 * The owner is read from [ActiveUserProvider] at call time rather than captured
 * at construction, because the same singleton serves every signed-in account
 * over the app's lifetime. When no account is active (signed out, or the
 * anonymous/local session of decision D5) both operations are refused: the
 * anonymous path persists nothing.
 */
class AppInternalCoverStorage(
    context: Context,
    private val activeUser: ActiveUserProvider,
) : CoverStorage {
    private val layout = AccountStorageLayout(context.filesDir)

    override suspend fun saveCover(
        bookId: String,
        coverBytes: ByteArray,
    ): Result<String> =
        runCatching {
            withContext(Dispatchers.IO) {
                val userId =
                    activeUser.currentUserId()
                        ?: throw IllegalStateException("No active account: covers are not persisted on the anonymous path (D5)")
                val coverFile = layout.coverFile(userId, bookId)
                coverFile.parentFile?.mkdirs()
                coverFile.writeBytes(coverBytes)
                coverFile.absolutePath
            }
        }

    override suspend fun deleteCover(bookId: String): Result<Unit> =
        runCatching {
            withContext(Dispatchers.IO) {
                val userId = activeUser.currentUserId() ?: return@withContext
                val coverFile = layout.coverFile(userId, bookId)
                if (coverFile.exists()) {
                    coverFile.delete()
                }
            }
        }
}
