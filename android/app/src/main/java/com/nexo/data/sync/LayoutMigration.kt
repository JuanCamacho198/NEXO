package com.nexo.data.sync

import com.nexo.data.storage.AccountStorageLayout
import java.io.File

/**
 * WU6 version-gated, idempotent Android layout migration (FR-01, FR-02, FR-03,
 * INV-5), extended for per-account isolation (T8 of `aislamiento-datos-por-cuenta`).
 *
 * The legacy tree splits library files across `filesDir/catalog` (catalog
 * downloads were both staged and stored there), `filesDir/pdfs`, and
 * `filesDir/epubs`; imported bytes are referenced directly by the row's
 * `file_path`. The target tree stores every book under
 * `filesDir/books/{userId}` and keeps covers under `filesDir/covers/{userId}`,
 * with new import/download staging in `cacheDir`.
 *
 * Each run is scoped to ONE owner: [LayoutMigrationRunner] walks the accounts
 * that own a stored path and runs the engine once per owner, so a migration can
 * never sweep a file into another account's subtree.
 *
 * Order of operations is the safety mechanism: move files first, rewrite the
 * stored paths second, run the verification pass third, and bump `layout.json`
 * ONLY after verification succeeds. Every move skips an already-present
 * destination, so an interrupted run resumes without duplicating or losing a
 * file. A failed verification leaves the marker unbumped and the app on the
 * legacy tree.
 *
 * Pure JVM (no Android APIs) so the whole engine is unit-testable with temp
 * directories.
 */
sealed interface LayoutMigrationResult {
    data class AlreadyCurrent(
        val version: Int,
    ) : LayoutMigrationResult

    data class Migrated(
        val version: Int,
        val movedFiles: Int,
    ) : LayoutMigrationResult

    data class VerificationFailed(
        val missing: List<String>,
    ) : LayoutMigrationResult
}

class LayoutMigration(
    private val filesDir: File,
    private val cacheDir: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val layout = AccountStorageLayout(filesDir)

    val stagingDir: File get() = File(cacheDir, STAGING_DIR)
    val markerFile: File get() = File(filesDir, MARKER_FILE)

    /** Missing marker means version 0 (legacy tree), never a clean install. */
    fun currentVersion(): Int {
        if (!markerFile.isFile) return 0
        val text = runCatching { markerFile.readText() }.getOrNull() ?: return 0
        val match = VERSION_PATTERN.find(text) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }

    /**
     * Run the migration for [userId]'s files. [storedPaths] are the raw
     * `books.file_path` values owned by [userId]; [rewriteStoredPaths] persists
     * the old->new mapping in one transaction.
     */
    suspend fun migrate(
        userId: String,
        storedPaths: List<String>,
        rewriteStoredPaths: suspend (Map<String, String>) -> Unit,
    ): LayoutMigrationResult {
        val version = currentVersion()
        if (version >= LAYOUT_VERSION) return LayoutMigrationResult.AlreadyCurrent(version)

        val booksDir = layout.booksDir(userId)
        val coversDir = layout.coversDir(userId)
        booksDir.mkdirs()
        coversDir.mkdirs()
        stagingDir.mkdirs()

        var moved = 0
        for (legacy in LEGACY_DIRS) {
            val dir = layout.legacyDir(legacy)
            if (!dir.isDirectory) continue
            for (file in dir.listFiles().orEmpty()) {
                if (!file.isFile) continue
                // Only this owner's files move into this owner's subtree: a file
                // referenced by the owner's rows, or (for an unowned legacy tree)
                // every file when this is the sole owner pass.
                if (!ownsFile(file, storedPaths)) continue
                if (moveFile(file, File(booksDir, file.name))) moved++
            }
        }

        // Covers already live at `filesDir/covers` (flat). Move the owner's
        // covers into the per-account subtree.
        val flatCovers = layout.legacyDir(AccountStorageLayout.COVERS_DIR)
        if (flatCovers.isDirectory) {
            for (file in flatCovers.listFiles().orEmpty()) {
                if (!file.isFile) continue
                if (!ownsCover(file, storedPaths)) continue
                if (moveFile(file, File(coversDir, file.name))) moved++
            }
        }

        val mapping = LinkedHashMap<String, String>()
        for (path in storedPaths) {
            if (!isLegacyPath(path)) continue
            val target = File(booksDir, File(path).name)
            mapping[path] = target.path
        }
        if (mapping.isNotEmpty()) rewriteStoredPaths(mapping)

        val missing =
            storedPaths.mapNotNull { old ->
                val resolved = mapping[old] ?: old
                if (File(resolved).exists()) null else resolved
            }
        if (missing.isNotEmpty()) return LayoutMigrationResult.VerificationFailed(missing)

        writeVersion()
        return LayoutMigrationResult.Migrated(LAYOUT_VERSION, moved)
    }

    /** True when [file] is one of [storedPaths]' backing files. */
    private fun ownsFile(
        file: File,
        storedPaths: List<String>,
    ): Boolean = storedPaths.any { it == file.path } || storedPaths.isEmpty()

    /**
     * A cover belongs to an owner when the same book id appears in a stored
     * path (`{bookId}.jpg` next to `{bookId}.{ext}`).
     */
    private fun ownsCover(
        file: File,
        storedPaths: List<String>,
    ): Boolean {
        if (storedPaths.isEmpty()) return true
        val bookId = file.nameWithoutExtension
        return storedPaths.any { File(it).nameWithoutExtension == bookId }
    }

    private fun isLegacyPath(path: String): Boolean =
        LEGACY_DIRS.any { legacy ->
            val prefix = layout.legacyDir(legacy).path + File.separator
            path.startsWith(prefix)
        }

    /** Idempotent move: a present destination wins and the source is dropped. */
    private fun moveFile(
        source: File,
        target: File,
    ): Boolean {
        if (!source.exists()) return false
        target.parentFile?.mkdirs()
        if (target.exists()) {
            source.delete()
            return false
        }
        return source.renameTo(target)
    }

    private fun writeVersion() {
        val tmp = File(filesDir, "$MARKER_FILE.tmp")
        tmp.writeText("{\"version\":$LAYOUT_VERSION,\"migratedAt\":${nowMillis()}}")
        if (!tmp.renameTo(markerFile)) {
            markerFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val LAYOUT_VERSION: Int = 1
        const val MARKER_FILE: String = "layout.json"
        const val STAGING_DIR: String = "catalog"
        val LEGACY_DIRS: List<String> = listOf("catalog", "pdfs", "epubs")

        private val VERSION_PATTERN = Regex("\"version\"\\s*:\\s*(\\d+)")
    }
}
