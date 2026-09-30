package com.nexo.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Unit tests for [computeSha256] — SHA-256 computation utility.
 *
 * Verifies:
 * - Correct hash for known content
 * - Prefix format "sha256:{hex}"
 * - Graceful failure on nonexistent files
 */
class ContentHashTest {
    @Test
    fun computeSha256_returnsCorrectHashForKnownContent() {
        val file = createTempFileWithContent("Hello, World!")
        val hash = computeSha256(file.absolutePath)

        assertNotNull(hash)
        assertTrue(hash!!.startsWith("sha256:"))
        // SHA-256 of "Hello, World!" (verified independently):
        // dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f
        assertEquals(
            "sha256:dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f",
            hash,
        )
    }

    @Test
    fun computeSha256_returnsNullForNonExistentFile() {
        val hash = computeSha256("/nonexistent/file.epub")
        assertNull(hash)
    }

    @Test
    fun computeSha256_prefixesWithSha256Algorithm() {
        val file = createTempFileWithContent("Content hash test — nexo")
        val hash = computeSha256(file.absolutePath)

        assertNotNull(hash)
        assertTrue(hash!!.startsWith("sha256:"))
        val hexPart = hash.removePrefix("sha256:")
        assertEquals("SHA-256 hex string should be 64 characters", 64, hexPart.length)
        assertTrue("Hex part should contain only hex chars", hexPart.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun computeSha256_producesSameHashForSameContent() {
        val content = "Same content across two files — hash should match"
        val file1 = createTempFileWithContent(content)
        val file2 = createTempFileWithContent(content)

        val hash1 = computeSha256(file1.absolutePath)
        val hash2 = computeSha256(file2.absolutePath)

        assertNotNull(hash1)
        assertNotNull(hash2)
        assertEquals("Same content should produce same hash", hash1, hash2)
    }

    @Test
    fun computeSha256_producesDifferentHashForDifferentContent() {
        val file1 = createTempFileWithContent("Content A")
        val file2 = createTempFileWithContent("Content B")

        val hash1 = computeSha256(file1.absolutePath)
        val hash2 = computeSha256(file2.absolutePath)

        assertNotNull(hash1)
        assertNotNull(hash2)
        assertTrue(
            "Different content should produce different hash",
            hash1 != hash2,
        )
    }

    private fun createTempFileWithContent(content: String): File {
        val file = File.createTempFile("content-hash-test-", ".bin")
        file.deleteOnExit()
        FileOutputStream(file).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
        }
        return file
    }
}
