package com.nexo.debug

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLogStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    fun `write appends line to log file`() {
        val (store, logDir) = createStore()

        store.write("INFO", "Test", "hello", 1000L)

        val log0 = File(logDir, "log.0.txt")
        assertTrue("log.0.txt should exist", log0.exists())
        val content = log0.readText()
        assertTrue("File should contain the written line", content.contains("1000 INFO Test: hello"))
    }

    @Test
    fun `rotation creates new file when exceeding maxFileSize`() {
        val (store, logDir) = createStore(maxFileSize = 100L)
        val line = "A".repeat(50)

        // Write enough to exceed 100 bytes — about 3 lines triggers rotation
        repeat(5) { i ->
            store.write("INFO", "T", "$line #$i", i.toLong())
        }

        val log0 = File(logDir, "log.0.txt")
        val log1 = File(logDir, "log.1.txt")

        assertTrue("log.0.txt should exist after rotation", log0.exists())
        assertTrue("log.1.txt should exist after rotation", log1.exists())
        assertTrue("log.0.txt should be ≤ 200KB after rotation", log0.length() <= 200L * 1024)
    }

    @Test
    fun `snapshot returns newest first with max 200 lines`() {
        val (store, _) = createStore(maxFileSize = 500_000)

        repeat(300) { i ->
            store.write("INFO", "T", "line $i", i.toLong())
        }

        val snapshot = store.snapshot()

        assertTrue("Snapshot should have at most 200 lines", snapshot.size <= 200)
        assertEquals("line 299", extractMessage(snapshot.first()))
        assertEquals("line 100", extractMessage(snapshot.last()))
    }

    @Test
    fun `cleanup deletes oldest crash files beyond max`() {
        val (store, _) = createStore()
        val crashDir = tempFolder.newFolder("crashes")

        // Create 11 crash files with staggered timestamps (recent past,
        // distinct minutes - ancient epoch values are unreliable on some
        // Linux filesystems and made this test flaky on CI).
        val now = System.currentTimeMillis()
        repeat(11) { i ->
            val file = File(crashDir, "crash_$i.txt")
            check(file.createNewFile()) { "failed to create ${file.name}" }
            check(file.setLastModified(now - (11 - i) * 60_000L)) {
                "failed to set mtime on ${file.name}"
            }
        }

        val before =
            crashDir
                .listFiles()
                ?.filter { it.name.startsWith("crash_") }
                ?: error("crash dir not listable")
        check(before.size == 11) { "expected 11 seed files, got ${before.size}" }
        val oldestName = before.minBy { it.lastModified() }.name

        store.cleanup(crashDir, maxFiles = 10)

        val remaining =
            crashDir
                .listFiles()
                ?.filter { it.name.startsWith("crash_") }
                ?: error("crash dir not listable after cleanup")
        check(remaining.size == 10) {
            "Should have 10 crash files remaining, got ${remaining.size}: " +
                remaining.joinToString(", ") { "${it.name}@${it.lastModified()}" } +
                " (seed: " + before.joinToString(", ") { "${it.name}@${it.lastModified()}" } + ")"
        }
        val deleted = before.map { it.name } - remaining.map { it.name }.toSet()
        check(deleted == listOf(oldestName)) {
            "Oldest ($oldestName) should be deleted; deleted=$deleted"
        }
    }

    @Test
    fun `write is thread safe under concurrent writes`() =
        runBlocking {
            val (store, _) = createStore(maxFileSize = 500_000) // no rotation during test
            val numThreads = 10
            val linesPerThread = 10 // 100 total — fits under snapshot cap of 200

            val jobs =
                (1..numThreads).map { threadId ->
                    async(Dispatchers.Default) {
                        repeat(linesPerThread) { lineNum ->
                            store.write(
                                "INFO",
                                "T$threadId",
                                "data $lineNum",
                                (threadId * linesPerThread + lineNum).toLong(),
                            )
                        }
                    }
                }
            jobs.awaitAll()

            val expectedTotal = numThreads * linesPerThread
            val snapshot = store.snapshot()
            assertEquals("Snapshot should contain all written lines", expectedTotal, snapshot.size)

            // Every written line should appear exactly once in the snapshot
            val lines = snapshot.toSet()
            assertEquals(
                "All lines should be unique (no interleaving corruption)",
                expectedTotal,
                lines.size,
            )
        }

    @Test
    fun `copySnapshotTo writes snapshot content to target file`() {
        val (store, _) = createStore()

        store.write("WARN", "Test", "msg1", 100L)
        store.write("ERROR", "Test", "msg2", 200L)

        val target = tempFolder.newFile("crash_copy.txt")
        store.copySnapshotTo(target)

        val content = target.readText()
        assertTrue("Target should contain first log line", content.contains("100 WARN Test: msg1"))
        assertTrue("Target should contain second log line", content.contains("200 ERROR Test: msg2"))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun createStore(maxFileSize: Long = 200L * 1024): Pair<CrashLogStore, File> {
        val logDir = tempFolder.newFolder("logs")
        return CrashLogStore(logDir, maxFileSize) to logDir
    }

    private fun extractMessage(line: String): String {
        val colonIdx = line.indexOf(": ")
        return if (colonIdx >= 0) line.substring(colonIdx + 2) else line
    }
}
