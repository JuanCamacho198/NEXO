package com.nexo.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * WU6 (FR-01, FR-02, INV-5): the Android layout migration is version-gated,
 * idempotent, verifies before bumping, and keeps the legacy tree on a failed
 * verification.
 */
class LayoutMigrationTest {
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private val fixedNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("nexo-layout-files").toFile()
        cacheDir = Files.createTempDirectory("nexo-layout-cache").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    private fun migration() = LayoutMigration(filesDir, cacheDir) { fixedNow }

    private fun legacyFile(
        dir: String,
        name: String,
    ): File {
        val directory = File(filesDir, dir).apply { mkdirs() }
        return File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun missingMarker_isVersionZero() {
        assertEquals(0, migration().currentVersion())
    }

    @Test
    fun freshInstall_bumpsVersionWithNoFiles() =
        runBlocking {
            val result = migration().migrate(emptyList()) { }

            assertEquals(LayoutMigrationResult.Migrated(1, 0), result)
            assertEquals(1, migration().currentVersion())
        }

    @Test
    fun migrate_movesLegacyDirs_rewritesPaths_andBumpsLast() =
        runBlocking {
            val stored = legacyFile("catalog", "book.epub")
            val pdf = legacyFile("pdfs", "doc.pdf")
            val oldPaths = listOf(stored.path, pdf.path)
            var rewritten: Map<String, String> = emptyMap()

            val result =
                migration().migrate(oldPaths) { mapping ->
                    rewritten = mapping
                }

            assertTrue(result is LayoutMigrationResult.Migrated)
            assertEquals(2, rewritten.size)
            assertTrue(File(rewritten.getValue(stored.path)).exists())
            assertTrue(File(filesDir, "books/book.epub").exists())
            assertTrue(File(filesDir, "books/doc.pdf").exists())
            assertEquals(1, migration().currentVersion())
        }

    @Test
    fun migrate_isIdempotent() =
        runBlocking {
            legacyFile("catalog", "book.epub")
            migration().migrate(listOf(File(filesDir, "catalog/book.epub").path)) { }

            val second = migration().migrate(emptyList()) { }

            assertEquals(LayoutMigrationResult.AlreadyCurrent(1), second)
            val files =
                File(filesDir, "books")
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .sorted()
            assertEquals(listOf("book.epub"), files)
        }

    @Test
    fun interruptedMigration_resumesWithoutLoss() =
        runBlocking {
            // The file already moved; the marker was never written.
            File(filesDir, "books").mkdirs()
            val moved = File(filesDir, "books/book.epub").apply { writeBytes(byteArrayOf(7)) }
            val oldPath = File(filesDir, "catalog/book.epub").path

            val result = migration().migrate(listOf(oldPath)) { }

            assertTrue(result is LayoutMigrationResult.Migrated)
            assertTrue(moved.exists())
            assertEquals(1, migration().currentVersion())
        }

    @Test
    fun failedVerification_keepsMarkerUnbumped() =
        runBlocking {
            val result =
                migration().migrate(listOf(File(filesDir, "catalog/ghost.epub").path)) { }

            assertTrue(result is LayoutMigrationResult.VerificationFailed)
            assertEquals(0, migration().currentVersion())
        }

    @Test
    fun coversTargetDir_isCreated() =
        runBlocking {
            migration().migrate(emptyList()) { }

            assertTrue(File(filesDir, "covers").isDirectory)
        }

    @Test
    fun stagingDir_livesUnderCache() =
        runBlocking {
            migration().migrate(emptyList()) { }

            assertTrue(File(cacheDir, "catalog").isDirectory)
            assertFalse(File(filesDir, "catalog/book.epub").exists())
        }
}
