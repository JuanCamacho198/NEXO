package com.nexo.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class DebugToolkitTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── clearCache ─────────────────────────────────────────────────────────

    @Test
    fun `clearCache removes cache children but leaves library data intact`() {
        val cacheDir = tempFolder.newFolder("cache")
        val libraryDir = tempFolder.newFolder("files", "books")
        File(cacheDir, "coil-images").mkdirs()
        File(File(cacheDir, "coil-images"), "cover.png").writeText("image-bytes")
        File(cacheDir, "epub_cache").mkdirs()
        File(File(cacheDir, "epub_cache"), "chapter.xhtml").writeText("chapter")
        File(cacheDir, "catalog.json").writeText("catalog")
        File(libraryDir, "book.epub").writeText("precious-library-data")

        val report = DebugToolkit.clearCache(cacheDir)

        assertEquals(3, report.deletedEntries)
        assertTrue("deletedBytes should count regular files", report.deletedBytes > 0L)
        assertEquals(0, report.failedEntries)
        assertTrue("cache dir itself must remain", cacheDir.exists())
        assertEquals(0, cacheDir.listFiles()?.size ?: 0)
        assertTrue("library file must survive", File(libraryDir, "book.epub").exists())
    }

    @Test
    fun `clearCache reports zeros for empty or missing directory`() {
        val emptyDir = tempFolder.newFolder("empty-cache")
        val emptyReport = DebugToolkit.clearCache(emptyDir)
        assertEquals(CacheClearReport(0, 0L, 0), emptyReport)

        val missingReport = DebugToolkit.clearCache(File(tempFolder.root, "does-not-exist"))
        assertEquals(CacheClearReport(0, 0L, 0), missingReport)
    }

    // ── recentErrors ───────────────────────────────────────────────────────

    @Test
    fun `recentErrors parses newest first and scrubs PII`() {
        val crashDir = tempFolder.newFolder("crashes")
        writeCrash(crashDir, "crash_1.txt", timestamp = 1000L, message = "old failure")
        writeCrash(
            crashDir,
            "crash_2.txt",
            timestamp = 2000L,
            message = "contact dev@example.com id 123e4567-e89b-12d3-a456-426614174000",
        )
        File(crashDir, "notes.txt").writeText("not a crash")

        val entries = DebugToolkit.recentErrors(crashDir)

        assertEquals(2, entries.size)
        assertEquals(2000L, entries[0].timestampMs)
        assertEquals(1000L, entries[1].timestampMs)
        assertFalse(entries[0].message.contains("dev@example.com"))
        assertFalse(entries[0].message.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(entries[0].message.contains(DebugToolkit.PII_REDACTED))
    }

    @Test
    fun `recentErrors respects the limit and a non-positive limit returns empty`() {
        val crashDir = tempFolder.newFolder("crashes")
        repeat(4) { i -> writeCrash(crashDir, "crash_$i.txt", timestamp = i.toLong(), message = "err $i") }

        assertEquals(2, DebugToolkit.recentErrors(crashDir, limit = 2).size)
        assertTrue(DebugToolkit.recentErrors(crashDir, limit = 0).isEmpty())
        assertTrue(DebugToolkit.recentErrors(tempFolder.newFolder("empty"), limit = 5).isEmpty())
    }

    // ── scrubForDisplay ────────────────────────────────────────────────────

    @Test
    fun `scrubForDisplay redacts keyed values, emails and uuids`() {
        val raw = "email: dev@example.com userid: 123e4567-e89b-12d3-a456-426614174000"

        val scrubbed = DebugToolkit.scrubForDisplay(raw)

        assertFalse(scrubbed.contains("dev@example.com"))
        assertFalse(scrubbed.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(scrubbed.contains(DebugToolkit.PII_REDACTED))
    }

    @Test
    fun `formatTimestamp emits a stable pattern`() {
        val formatted = DebugToolkit.formatTimestamp(0L, Locale.US)
        assertTrue(
            "unexpected format: $formatted",
            Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(formatted),
        )
    }

    private fun writeCrash(
        dir: File,
        name: String,
        timestamp: Long,
        message: String,
    ) {
        File(dir, name).writeText(
            buildString {
                appendLine("Timestamp: $timestamp")
                appendLine("Thread: main")
                appendLine("Message: $message")
                appendLine("--- Stack Trace ---")
                appendLine("at com.nexo.Fake.fail(Fake.kt:1)")
            },
        )
    }
}
