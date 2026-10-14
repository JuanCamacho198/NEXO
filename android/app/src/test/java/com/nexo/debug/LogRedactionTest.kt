package com.nexo.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RED fixtures for the universal redaction boundary. Any path that bypasses
 * [LogRedaction] — or a regression that reopens a pattern — fails here.
 */
class LogRedactionTest {
    private val cleanUrl = "https://oauth.example.com/cb?code=keep_me"

    private val fixture =
        "api_key:live_secret_123 dsn:https://abc@o1.ingest.sentry.io/123 " +
            "supabase:https://proj.supabase.co C:\\Users\\juan\\books\\novel.epub " +
            "/home/juan/books/novel.epub ~/books/novel.epub $cleanUrl"

    @Test
    fun `redactString removes every raw secret and path`() {
        val out = LogRedaction.redactString(fixture)

        for (raw in listOf("live_secret_123", "o1.ingest.sentry.io", "proj.supabase.co", "novel.epub", "juan", "Users")) {
            assertFalse("raw value leaked: $raw\n$out", out.contains(raw))
        }
        assertTrue(out.contains(LogRedaction.REDACTED))
        assertTrue(out.contains(LogRedaction.REDACTED_PATH))
    }

    @Test
    fun `clean URL survives intact`() {
        val out = LogRedaction.redactString(fixture)
        assertTrue("clean URL must survive: $out", out.contains(cleanUrl))
        assertEquals(cleanUrl, LogRedaction.redactString(cleanUrl))
    }

    @Test
    fun `all fifteen patterns redact their keyed value`() {
        for (pattern in LogRedaction.PATTERNS) {
            val out = LogRedaction.redactString("$pattern:SuperSecret42 tail")
            assertFalse("pattern $pattern did not redact", out.contains("SuperSecret42"))
            assertTrue("pattern $pattern missing marker: $out", out.contains(LogRedaction.REDACTED))
        }
    }

    @Test
    fun `keyed value stops at whitespace comma or brace`() {
        assertEquals("token:[REDACTED],next", LogRedaction.redactString("token:abc,next"))
        assertEquals("x}token:[REDACTED]}", LogRedaction.redactString("x}token:abc}"))
        assertEquals("password:[REDACTED]", LogRedaction.redactString("password:"))
    }

    @Test
    fun `pattern embedded in a longer word is not a key`() {
        assertEquals("mytoken:value", LogRedaction.redactString("mytoken:value"))
    }

    @Test
    fun `redaction is idempotent`() {
        val once = LogRedaction.redactString(fixture)
        assertEquals(once, LogRedaction.redactString(once))
    }

    @Test
    fun `redactValue redacts sensitive keys and recurses into nested maps`() {
        val input =
            mapOf(
                "supabaseKey" to "sb_live_xyz",
                "token_header" to "Bearer abc",
                "highlight" to "the answer is 42",
                "bookContent" to "chapter one body",
                "safe_field" to "keep me",
                "nested" to mapOf("url" to "https://api.example.com/v1/books", "userToken" to "tok_123"),
            )

        @Suppress("UNCHECKED_CAST")
        val out = LogRedaction.redactValue(input) as Map<String, Any?>

        assertEquals(LogRedaction.REDACTED, out["supabaseKey"])
        assertEquals(LogRedaction.REDACTED, out["token_header"])
        assertEquals(LogRedaction.REDACTED, out["highlight"])
        assertEquals(LogRedaction.REDACTED, out["bookContent"])
        assertEquals("keep me", out["safe_field"])

        @Suppress("UNCHECKED_CAST")
        val nested = out["nested"] as Map<String, Any?>
        assertEquals("https://api.example.com/v1/books", nested["url"])
        assertEquals(LogRedaction.REDACTED, nested["userToken"])
    }

    @Test
    fun `redactValue maps arrays and leaves non-strings untouched`() {
        val out = LogRedaction.redactValue(listOf("token:abc", "plain", 7, null))
        assertEquals(listOf("token:[REDACTED]", "plain", 7, null), out)
    }

    @Test
    fun `isSensitiveKey is case-insensitive substring matching`() {
        assertTrue(LogRedaction.isSensitiveKey("Authorization"))
        assertTrue(LogRedaction.isSensitiveKey("x-api-key"))
        assertTrue(LogRedaction.isSensitiveKey("BOOK_CONTENT"))
        assertFalse(LogRedaction.isSensitiveKey("author"))
    }
}
