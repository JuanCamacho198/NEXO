package com.nexo.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the deterministic session id (T-B1, REQ-reading-sessions-sync-1/7).
 *
 * - Same inputs → same id (local Room PK, LWW REPLACE matches by id).
 * - Different startTime per flush → distinct ids (no double count).
 */
class ReadingSessionIdTest {
    @Test
    fun `readingSessionId is deterministic for identical inputs`() {
        val first = readingSessionId("user-1", "book-1", 1234L)
        val second = readingSessionId("user-1", "book-1", 1234L)
        assertEquals(first, second)
    }

    @Test
    fun `readingSessionId differs per flush start time`() {
        val first = readingSessionId("user-1", "book-1", 1234L)
        val second = readingSessionId("user-1", "book-1", 5678L)
        assertNotEquals(first, second)
    }

    @Test
    fun `readingSessionId differs across users`() {
        val first = readingSessionId("user-1", "book-1", 1234L)
        val second = readingSessionId("user-2", "book-1", 1234L)
        assertNotEquals(first, second)
    }

    @Test
    fun `readingSessionId differs across books`() {
        val first = readingSessionId("user-1", "book-1", 1234L)
        val second = readingSessionId("user-1", "book-2", 1234L)
        assertNotEquals(first, second)
    }

    @Test
    fun `readingSessionId has sess prefix and 32 hex chars`() {
        val id = readingSessionId("user-1", "book-1", 1234L)
        assertTrue("id should start with sess_", id.startsWith("sess_"))
        // "sess_" (5) + sha256 hex take(32)
        assertEquals(37, id.length)
        val hexPart = id.removePrefix("sess_")
        assertTrue("hex part must be lowercase hex", hexPart.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `readingSessionId handles blank userId safely`() {
        // Pre-auth flushes record with userId='' — must still produce a valid id.
        val id = readingSessionId("", "book-1", 1234L)
        assertTrue(id.startsWith("sess_"))
        assertEquals(37, id.length)
    }
}
