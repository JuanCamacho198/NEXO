package com.nexo.debug

import java.io.File

/**
 * [LogWriter] fake for unit tests.
 * Stores written lines in-memory; never touches disk.
 */
class FakeLogWriter : LogWriter {
    val written = mutableListOf<String>()
    var failWrites = false

    override fun write(
        level: String,
        tag: String,
        message: String,
        timestamp: Long,
    ) {
        if (failWrites) throw RuntimeException("Simulated write failure")
        written.add("$timestamp $level $tag: $message")
    }

    override fun snapshot(): List<String> = written.toList().asReversed().take(200)

    override fun copySnapshotTo(target: File) {
        val content = snapshot()
        target.parentFile?.mkdirs()
        target.bufferedWriter().use { writer ->
            content.forEach { line ->
                writer.write(line)
                writer.newLine()
            }
        }
    }
}
