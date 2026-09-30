package com.nexo.data.remote.drive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCatalogContractTest {
    private val legacy = LegacyRemoteCandidate("legacy-id", "old.epub") { "legacy".toByteArray() }
    private val canonical = LegacyRemoteCandidate("canonical-id", "book-1.epub") { "book".toByteArray() }

    @Test fun stableId_returnsCanonicalNameAndPath() =
        runBlocking {
            val r = resolve("legacy-id", listOf(legacy, canonical), "legacy")
            assertEquals("legacy-id", r?.fileId)
            assertEquals("book-1.epub", r?.fileName)
            assertEquals("Nexo/Books/book-1.epub", r?.canonicalPath)
        }

    @Test fun precedence_isIdThenNameThenHash() =
        runBlocking {
            assertEquals("legacy-id", resolve("legacy-id", listOf(legacy, canonical), "legacy")?.fileId)
            assertEquals("canonical-id", resolve(null, listOf(legacy, canonical), "book")?.fileId)
            assertEquals("legacy-id", resolve("missing", listOf(legacy), "legacy")?.fileId)
        }

    @Test fun unavailableSafe_returnsNullForMismatchOrDownloadFailure() =
        runBlocking {
            assertNull(resolve("legacy-id", listOf(legacy), "wrong"))
            val denied = LegacyRemoteCandidate("denied", "book-1.epub") { error("403") }
            assertNull(resolve("denied", listOf(denied), "book"))
        }

    @Test fun catalogRowWinner_isDeterministicByVersionThenUpdatedAtThenId() {
        fun row(
            id: String,
            v: Long,
            ts: String,
        ) = CatalogRow("u", id, "epub", "h", Lifecycle.AVAILABLE, null, v) to ts
        // Higher version wins.
        val (older, olderTs) = row("same", 3, "2025-01-01T00:00:00Z")
        val (newer, newerTs) = row("same", 4, "2025-06-01T00:00:00Z")
        assertEquals(4, catalogRowWinner(older, newer) { newerTs }.catalogVersion)
        // Equal version: newer updatedAt wins (row b returned).
        val (a, aTs) = row("same", 4, "2025-01-01T00:00:00Z")
        val (b, bTs) = row("same", 4, "2025-06-01T00:00:00Z")
        assertEquals("same", catalogRowWinner(a, b) { bTs }.bookId)
        assertEquals(4, catalogRowWinner(a, b) { bTs }.catalogVersion)
        // Equal version and updatedAt: greater id wins.
        val (c1, c1Ts) = row("book-a", 4, "2025-06-01T00:00:00Z")
        val (c2, c2Ts) = row("book-b", 4, "2025-06-01T00:00:00Z")
        assertEquals("book-b", catalogRowWinner(c1, c2) { c2Ts }.bookId)
    }

    @Test fun redactLogLine_stripsTokensJwtsAndHashesButKeepsCorrelation() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val line = "sync token=secret123 authorization=Bearer $jwt hash=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef correlation=abc-123"
        val redacted = redactLogLine(line)
        assertTrue(redacted.contains("token=[REDACTED]"))
        assertTrue(redacted.contains("authorization=[REDACTED]"))
        assertTrue(redacted.contains("[JWT_REDACTED]"))
        assertTrue(redacted.contains("[HASH_REDACTED]"))
        assertTrue(redacted.contains("correlation=abc-123"))
        assertFalse(redacted.contains("secret123"))
    }

    @Test fun syncErrorCodes_exportsStableCoverFailedCode() {
        // REQ-07: cover failures must never block book import and must be typed COVER_FAILED.
        assertEquals("COVER_FAILED", SyncErrorCodes.COVER_FAILED)
        val expected =
            setOf(
                "AUTH_REQUIRED",
                "AUTH_EXPIRED",
                "PERMISSION_DENIED",
                "REMOTE_NOT_FOUND",
                "HASH_MISMATCH",
                "CONFLICT",
                "UNAVAILABLE",
                "COVER_FAILED",
            )
        assertEquals(
            expected,
            setOf(
                SyncErrorCodes.AUTH_REQUIRED,
                SyncErrorCodes.AUTH_EXPIRED,
                SyncErrorCodes.PERMISSION_DENIED,
                SyncErrorCodes.REMOTE_NOT_FOUND,
                SyncErrorCodes.HASH_MISMATCH,
                SyncErrorCodes.CONFLICT,
                SyncErrorCodes.UNAVAILABLE,
                SyncErrorCodes.COVER_FAILED,
            ),
        )
    }

    @Test fun coverFailureError_mapsToStableCodeAndIsRetryable() {
        val err = coverFailureError("corr-1", "book-9")
        assertEquals("COVER_FAILED", err.code)
        assertTrue(err.retryable)
        assertEquals("corr-1", err.correlationId)
        assertEquals("book-9", err.bookId)
        val errNoBook = coverFailureError("corr-2")
        assertEquals("COVER_FAILED", errNoBook.code)
        assertNull(errNoBook.bookId)
    }

    private suspend fun resolve(
        id: String?,
        files: List<LegacyRemoteCandidate>,
        value: String,
    ) = DriveCatalogContract.reconcileLegacyReference(id, files, "book-1", "epub", "sha256:${sha256(value)}")

    private fun sha256(value: String) =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
