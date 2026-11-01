package com.nexo.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The Sentry feedback envelope must carry diagnostic logs WITHOUT carrying book
 * content.
 *
 * `build()` is exercised directly (no Sentry SDK needed) and the `unknown`
 * payload is read back, so the assertions describe exactly what egresses.
 */
class FeedbackCaptureLogsTest {
    private fun newEventId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun entry(text: String = "hello") =
        FeedbackEvent.FeedbackEntry(
            eventId = newEventId(),
            text = text,
            timestamp = System.currentTimeMillis(),
            bookId = "book-1",
            chapterIndex = 3,
            page = 142,
            title = "La Odisea",
            chapterLabel = "Capítulo IX",
        )

    @After
    fun tearDown() {
        FeedbackCapture.resetForTest()
    }

    @Suppress("UNCHECKED_CAST")
    private fun logsOf(entry: FeedbackEvent.FeedbackEntry): List<String>? {
        val unknown = FeedbackCapture.build(entry).unknown ?: return null
        return unknown["logs"] as? List<String>
    }

    @Test
    fun `no log source installed attaches no logs key`() {
        FeedbackCapture.resetForTest()
        assertNull(logsOf(entry()))
    }

    @Test
    fun `empty snapshot attaches no logs key`() {
        FeedbackCapture.installLogSource { emptyList() }
        assertNull(logsOf(entry()))
    }

    @Test
    fun `snapshot is attached oldest-first`() {
        // CrashLogStore.snapshot() is newest-first; the envelope must invert it
        // so the reader follows the thread of causes into the crash.
        FeedbackCapture.installLogSource { listOf("newest", "middle", "oldest") }

        assertEquals(listOf("oldest", "middle", "newest"), logsOf(entry()))
    }

    @Test
    fun `attached logs are capped keeping the newest lines`() {
        // snapshot() is newest-first, so the cap must drop the OLDEST lines —
        // the dense tail is what explains the crash.
        val newestFirst = (0 until 300).map { "line-$it" }
        FeedbackCapture.installLogSource { newestFirst }

        val logs = logsOf(entry())!!

        assertEquals(120, logs.size)
        assertTrue("newest line must survive the cap", logs.last() == "line-0")
        assertTrue("the prefix beyond the cap must be dropped", logs.first() == "line-119")
    }

    @Test
    fun `attached logs pass through LogRedaction`() {
        // Defence in depth: even a line that reached the store unredacted must
        // be scrubbed at egress.
        FeedbackCapture.installLogSource {
            listOf("token:tok_abc at C:\\Users\\juan\\books\\novel.epub")
        }

        val logs = logsOf(entry())!!

        assertEquals(1, logs.size)
        assertFalse("raw token leaked", logs[0].contains("tok_abc"))
        assertFalse("raw path leaked", logs[0].contains("novel.epub"))
        assertTrue("redaction marker expected", logs[0].contains(LogRedaction.REDACTED))
    }

    @Test
    fun `book context is still attached alongside the logs`() {
        FeedbackCapture.installLogSource { listOf("a crashy line") }

        val unknown = FeedbackCapture.build(entry()).unknown!!

        assertTrue("book context must survive", unknown.containsKey("book"))
        assertTrue("logs must be attached", unknown.containsKey("logs"))
    }

    @Test
    fun `a throwing log source does not break the feedback`() {
        FeedbackCapture.installLogSource { throw IllegalStateException("store exploded") }

        val feedback = FeedbackCapture.build(entry())

        assertNull("logs must be absent, not fatal", feedback.unknown?.get("logs"))
        assertTrue("book context must still be present", feedback.unknown!!.containsKey("book"))
    }
}
