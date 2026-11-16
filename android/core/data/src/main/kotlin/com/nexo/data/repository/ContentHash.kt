package com.nexo.data.repository

import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Compute SHA-256 hash of a file, prefixed with "sha256:" algorithm
 * prefix for future-proofing (e.g. future support for SHA-512).
 *
 * Returns null if the file cannot be read (non-fatal) — callers
 * should treat a null hash as "hash unavailable" and skip dedup.
 *
 * Used at import time to enable content-hash dedup across devices.
 */
private const val SHA256_BUFFER_SIZE = 8192

fun computeSha256(filePath: String): String? =
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(filePath).use { fis ->
            val buffer = ByteArray(SHA256_BUFFER_SIZE)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hexString = digest.digest().joinToString("") { "%02x".format(it) }
        "sha256:$hexString"
    } catch (_: Exception) {
        null
    }
