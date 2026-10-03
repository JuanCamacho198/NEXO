package com.nexo.data.sync

import java.io.File

/**
 * WU6 version-gated, idempotent Android layout migration (FR-01, FR-02, FR-03,
 * INV-5).
 *
 * The legacy tree splits library files across `filesDir/catalog` (catalog
 * downloads were both staged and stored there), `filesDir/pdfs`, and
 * `filesDir/epubs`; imported bytes are referenced directly by the row's
 * `file_path`. The target tree stores every book under `filesDir/books` and
 * keeps covers under `filesDir/covers` (already the live location), with new
 * import/download staging in `cacheDir`.
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
    val booksDir: File get() = File(filesDir, BOOKS_DIR)
    val coversDir: File get() = File(filesDir, COVERS_DIR)
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
     * Run the migration. [storedPaths] are the raw `books.file_path` values;
     * [rewriteStoredPaths] persists the old->new mapping in one transaction.
     */
    suspend fun migrate(
        storedPaths: List<String>,
        rewriteStoredPaths: suspend (Map<String, String>) -> Unit,
    ): LayoutMigrationResult {
        val version = currentVersion()
        if (version >= LAYOUT_VERSION) return LayoutMigrationResult.AlreadyCurrent(version)

        booksDir.mkdirs()
        coversDir.mkdirs()
        stagingDir.mkdirs()

        var moved = 0
        for (legacy in LEGACY_DIRS) {
            val dir = File(filesDir, legacy)
            if (!dir.isDirectory) continue
            for (file in dir.listFiles().orEmpty()) {
                if (!file.isFile) continue
                if (moveFile(file, File(booksDir, file.name))) moved++
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

    private fun isLegacyPath(path: String): Boolean =
        LEGACY_DIRS.any { legacy ->
            val prefix = File(filesDir, legacy).path + File.separator
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
        const val BOOKS_DIR: String = "books"
        const val COVERS_DIR: String = "covers"
        const val STAGING_DIR: String = "catalog"
        val LEGACY_DIRS: List<String> = listOf("catalog", "pdfs", "epubs")

        private val VERSION_PATTERN = Regex("\"version\"\\s*:\\s*(\\d+)")
    }
}
