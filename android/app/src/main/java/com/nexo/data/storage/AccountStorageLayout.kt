package com.nexo.data.storage

import java.io.File

/**
 * The one place that answers "where does this kind of file live, for this
 * account?" (T8 of `aislamiento-datos-por-cuenta`).
 *
 * Before this existed, library paths were flat and account-agnostic
 * (`filesDir/books/{bookId}.epub`, `filesDir/covers/{bookId}.jpg`). Two accounts
 * signing in on the same device resolved the *same* files, so the second
 * account's downloads overwrote the first account's books and covers. Every
 * path is now namespaced by owner: `books/{userId}/`, `covers/{userId}/`,
 * `pdfs/{userId}/`, `epubs/{userId}/`.
 *
 * `catalog/` is deliberately NOT account-scoped here. It is *staging* for an
 * in-flight catalog download (`cacheDir/catalog`, owned and cleaned up by
 * [com.nexo.domain.usecase.DownloadAndImportBookUseCase]); a file with a
 * deterministic per-book name lives there for seconds, is renamed atomically
 * into `books/{userId}/`, and is deleted on every exit path. It holds no
 * account data to collide over, so it stays a single global staging directory.
 *
 * Pure JVM (no Android APIs) so path resolution is unit-testable.
 */
class AccountStorageLayout(
    private val filesDir: File,
) {
    /** `books/{userId}`: imported and downloaded book binaries. */
    fun booksDir(userId: String): File = File(File(filesDir, BOOKS_DIR), ownerSegment(userId))

    /** `covers/{userId}`: extracted/persisted cover images. */
    fun coversDir(userId: String): File = File(File(filesDir, COVERS_DIR), ownerSegment(userId))

    /** `pdfs/{userId}`: imported PDF originals. */
    fun pdfsDir(userId: String): File = File(File(filesDir, PDFS_DIR), ownerSegment(userId))

    /** `epubs/{userId}`: imported EPUB originals. */
    fun epubsDir(userId: String): File = File(File(filesDir, EPUBS_DIR), ownerSegment(userId))

    /**
     * Every directory this layout manages for [userId], in sweep order. Used by
     * the orphan sweep so a walk can never escape the active account's subtree.
     */
    fun managedDirs(userId: String): List<File> =
        listOf(BOOKS_DIR, COVERS_DIR, PDFS_DIR, EPUBS_DIR).map { kind ->
            File(File(filesDir, kind), ownerSegment(userId))
        }

    /**
     * Directory the legacy (pre-account) layout used for [kind], e.g.
     * `filesDir/books`. Kept for the layout migration, which must still find
     * files written before the per-account tree existed.
     */
    fun legacyDir(kind: String): File = File(filesDir, kind)

    /**
     * `books/{userId}/{bookId}.{extension}` — the canonical backing-file path.
     * Distinct users always land in distinct directories, so the file name may
     * safely stay keyed on the book id.
     */
    fun bookFile(
        userId: String,
        bookId: String,
        extension: String,
    ): File = File(booksDir(userId), "$bookId.$extension")

    /** `covers/{userId}/{bookId}.jpg` — the canonical cover path. */
    fun coverFile(
        userId: String,
        bookId: String,
    ): File = File(coversDir(userId), "$bookId.jpg")

    /**
     * `{kind}/{userId}/{name}` — a file named by the *user's own* source
     * filename, which is why the owner segment (not the name) carries isolation.
     */
    fun namedFile(
        kind: String,
        userId: String,
        name: String,
    ): File = File(dirFor(kind, userId), name)

    /** Resolves the account-scoped directory for a managed [kind]. */
    fun dirFor(
        kind: String,
        userId: String,
    ): File =
        when (kind) {
            BOOKS_DIR -> booksDir(userId)
            COVERS_DIR -> coversDir(userId)
            PDFS_DIR -> pdfsDir(userId)
            EPUBS_DIR -> epubsDir(userId)
            else -> File(File(filesDir, kind), ownerSegment(userId))
        }

    /**
     * True when [path] points inside [userId]'s subtree for any managed kind.
     * Guards destructive walks (sweep, delete) against crossing accounts.
     */
    fun isInsideUserTree(
        path: String,
        userId: String,
    ): Boolean {
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        return managedDirs(userId).any { dir ->
            val root = runCatching { dir.canonicalFile }.getOrNull() ?: return@any false
            target == root || target.path.startsWith(root.path + File.separator)
        }
    }

    /**
     * The single owner segment. A blank id (no signed-in account) is not a real
     * owner: it cannot own files, so its directory is the reserved
     * [ANONYMOUS_SEGMENT] scratch space, which production never writes to —
     * the anonymous path is read-only (decision D5).
     */
    private fun ownerSegment(userId: String): String = userId.takeIf { it.isNotBlank() } ?: ANONYMOUS_SEGMENT

    companion object {
        const val BOOKS_DIR: String = "books"
        const val COVERS_DIR: String = "covers"
        const val PDFS_DIR: String = "pdfs"
        const val EPUBS_DIR: String = "epubs"

        /** Reserved segment for a caller with no owner; never written in production. */
        const val ANONYMOUS_SEGMENT: String = "_anonymous"
    }
}
