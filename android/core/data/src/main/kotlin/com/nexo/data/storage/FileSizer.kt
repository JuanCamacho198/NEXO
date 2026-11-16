package com.nexo.data.storage

import java.io.File

/**
 * File/folder size helpers shared by storage tooling.
 *
 * Kept dependency-free and Android-free so it is unit-testable on the JVM. The
 * storage screen consumes it for the books/cache breakdown; the orphan sweep
 * uses it when reporting reclaimed bytes.
 */
object FileSizer {
    /**
     * Total bytes of every regular file under [file], recursively. Returns 0
     * when [file] does not exist.
     */
    fun folderSize(file: File): Long {
        if (!file.exists()) return 0L
        return file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
