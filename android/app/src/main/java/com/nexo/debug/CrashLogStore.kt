package com.nexo.debug

import java.io.File

/**
 * Rotating file buffer implementing [LogWriter].
 *
 * Maintains two log files at `logDir/log.0.txt` (current) and `log.1.txt` (previous).
 * Each file is capped at [maxFileSize] bytes. When exceeded, `log.0.txt` is renamed
 * to `log.1.txt` and a new `log.0.txt` is created.
 *
 * Thread safety: [write] is `@Synchronized` — protects append + rotate.
 * [snapshot] and [copySnapshotTo] are lock-free (best-effort reads).
 */
class CrashLogStore(
    private val logDir: File,
    private val maxFileSize: Long = DEFAULT_MAX_FILE_SIZE,
) : LogWriter {
    private companion object {
        const val DEFAULT_MAX_FILE_SIZE = 200L * 1024L
        const val SNAPSHOT_LINE_LIMIT = 200
    }

    private val currentFile: File get() = File(logDir, "log.0.txt")
    private val previousFile: File get() = File(logDir, "log.1.txt")

    init {
        logDir.mkdirs()
    }

    @Synchronized
    override fun write(
        level: String,
        tag: String,
        message: String,
        timestamp: Long,
    ) {
        runCatching {
            val line = "$timestamp $level $tag: $message\n"
            currentFile.appendText(line)
            if (currentFile.length() > maxFileSize) {
                rotate()
            }
        }
    }

    @Synchronized
    private fun rotate() {
        runCatching {
            val current = currentFile
            val previous = previousFile
            if (previous.exists()) previous.delete()
            current.renameTo(previous)
            current.createNewFile()
        }
    }

    override fun snapshot(): List<String> {
        val lines = mutableListOf<String>()
        runCatching {
            // Read previous file first (older entries), then current (newer entries)
            if (previousFile.exists()) {
                previousFile.useLines { seq -> lines.addAll(seq) }
            }
            if (currentFile.exists()) {
                currentFile.useLines { seq -> lines.addAll(seq) }
            }
        }
        // Return newest first, capped at 200 lines
        return lines.reversed().take(SNAPSHOT_LINE_LIMIT)
    }

    override fun copySnapshotTo(target: File) {
        val snapshot = snapshot()
        runCatching {
            target.parentFile?.mkdirs()
            target.bufferedWriter().use { writer ->
                snapshot.forEach { line ->
                    writer.write(line)
                    writer.newLine()
                }
            }
        }
    }

    /**
     * Deletes oldest crash files in [crashDir] (matching `crash_*.txt`)
     * when count exceeds [maxFiles].
     */
    fun cleanup(
        crashDir: File,
        maxFiles: Int = 10,
    ) {
        runCatching {
            val files =
                crashDir
                    .listFiles()
                    ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
                    ?.sortedBy { it.lastModified() }
                    ?: return
            val mutable = files.toMutableList()
            while (mutable.size > maxFiles) {
                mutable.removeAt(0).delete()
            }
        }
    }
}
