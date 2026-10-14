package com.nexo.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G1/G3: the crash-export surfaces (notification intent + detail activity)
 * must scrub the same secrets/paths the log pipeline scrubs. The crash JSON is
 * NOT scrubbed at its raw source in the old data, so these boundaries apply
 * [LogRedaction] and must not leak a planted secret or an absolute path.
 */
class CrashExportRedactionTest {
    private val secretFixture =
        "api_key:live_secret_123 request failed at C:\\Users\\juan\\books\\novel.epub"

    @Test
    fun `notification envelope strips secrets and paths`() {
        val raw =
            JSONObject().apply {
                put("timestamp", 1L)
                put("threadName", "main")
                put("message", secretFixture)
                put("stackTrace", "at foo(C:\\Users\\juan\\books\\novel.epub:12)\ntoken:tok_abc")
            }

        val scrubbed = CrashNotificationHelper.redactJsonObject(raw).toString()

        for (raw in listOf("live_secret_123", "tok_abc", "novel.epub", "Users\\juan", "C:\\Users")) {
            assertFalse("raw value '$raw' leaked into the notification envelope", scrubbed.contains(raw))
        }
        assertTrue("redaction marker expected", scrubbed.contains(LogRedaction.REDACTED))
    }

    @Test
    fun `detail-activity boundary scrub is idempotent`() {
        // The detail screen scrubs the individual JSON fields with redactString;
        // re-applying must be a no-op (its markers carry neither a keyed value
        // nor a path shape).
        val scrubbed = LogRedaction.redactString(secretFixture)
        assertEquals(scrubbed, LogRedaction.redactString(scrubbed))
        assertFalse(scrubbed.contains("live_secret_123"))
    }

    @Test
    fun `structured payload scrubs sensitive keys wholesale`() {
        val payload =
            JSONObject().apply {
                put("message", "ok")
                put("api_key", "live_secret_123")
            }
        val scrubbed = CrashNotificationHelper.redactJsonObject(payload)
        assertEquals(LogRedaction.REDACTED, scrubbed.optString("api_key"))
        assertEquals("ok", scrubbed.optString("message"))
    }
}
