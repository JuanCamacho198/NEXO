package com.nexo.debug

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PII redaction layer tests (pii-redaction spec). Table-driven denylist test
 * over the exact sensitive field set, a "real event from each P0
 * instrumentation point" fixture asserting zero forbidden keys survive,
 * idempotency, and the breadcrumb allowlist.
 */
class SentryPiiScrubberTest {
    private val sensitiveFields =
        mapOf(
            "bookTitle" to "The Great Book",
            "bookId" to "book-123",
            "title" to "The Great Book",
            "author" to "Some Author",
            "isbn" to "9780000000000",
            "cfi" to "epubcfi(/6/4!/4/2:0)",
            "locator" to "epubcfi(/6/4)",
            "filePath" to "/storage/emulated/0/Android/data/user/book.epub",
            "bookPath" to "/data/user/0/files/book.epub",
            "epubPath" to "/data/book.epub",
            "highlight" to "user typed highlight text",
            "noteText" to "user typed note",
            "query" to "search term",
            "email" to "user@example.com",
            "userId" to "user-uuid-123",
            "password" to "hunter2",
            "access_token" to "eyJhbGciOi",
        )

    private fun eventWithExtras(extras: Map<String, Any>): SentryEvent = SentryEvent().apply { this.extras = LinkedHashMap(extras) }

    private val feedbackOnlyKeys = setOf("bookTitle")

    @Test
    fun `every sensitive field is redacted or dropped`() {
        for ((key, value) in sensitiveFields) {
            val scrubbed = SentryPiiScrubber.scrubEvent(eventWithExtras(mapOf(key to value)))
            val out = scrubbed.extras
            val original = out?.get(key)?.toString() ?: ""
            if (SentryPiiScrubber.PATH_KEYS.any { key.lowercase().contains(it) }) {
                assertNull("path key $key must be dropped entirely", out?.get(key))
            } else if (key in feedbackOnlyKeys) {
                // feedback-only keys are STRIPPED on non-feedback events (desktop parity)
                assertTrue("key $key must not survive", !original.contains(value))
            } else {
                assertEquals("key $key must be [Redacted]", "[Redacted]", out?.get(key))
            }
            assertFalse(asciiOf(out?.get(key)).contains(value))
        }
    }

    private fun asciiOf(v: Any?): String = v?.toString() ?: ""

    @Test
    fun `real event fixtures from each P0 instrumentation point keep no forbidden keys`() {
        val forbiddenValues = sensitiveFields.values.toList()

        // Cold start (app_shell) with an errant book-id tag — call-site error case.
        val coldStart =
            SentryEvent().apply {
                level = SentryLevel.INFO
                tags =
                    mapOf(
                        "platform" to "android",
                        "source" to "app_shell",
                        "bookId" to "book-123", // call-site error — must be redacted
                    )
            }
        val s1 = SentryPiiScrubber.scrubEvent(coldStart)
        assertEquals("[Redacted]", s1.tags?.get("bookId"))
        assertEquals("android", s1.tags?.get("platform"))

        // Reader open with a leaking exception message.
        val readerOpen =
            SentryEvent().apply {
                level = SentryLevel.ERROR
                exceptions =
                    listOf(
                        io.sentry.protocol.SentryException().apply {
                            value = "cfi=epubcfi(/6/4!/4/2:0) token:abc123"
                        },
                    )
            }
        val s2 = SentryPiiScrubber.scrubEvent(readerOpen)
        val msg = s2.exceptions?.firstOrNull()?.value ?: ""
        assertTrue("cfi-not-redacted msg=$msg", !msg.contains("epubcfi(/6"))
        assertFalse(msg.contains("abc123"))
        assertTrue("no-redacted-marker msg=$msg", msg.contains("[Redacted]"))

        // All scrubbed events must not contain any forbidden raw value anywhere.
        for (scrubbed in listOf(s1, s2)) {
            val dumped = asciiOf(scrubbed.extras) + asciiOf(scrubbed.tags)
            for (forbidden in forbiddenValues) {
                assertFalse(dumped.contains(forbidden))
            }
        }
    }

    @Test
    fun `redaction is idempotent`() {
        val event =
            eventWithExtras(
                mapOf(
                    "bookTitle" to "The Great Book",
                    "token" to "abc.def.ghi",
                    "message" to "token:secret-value",
                ),
            )
        val once = SentryPiiScrubber.scrubEvent(event)
        val twice = SentryPiiScrubber.scrubTwice(once)
        assertEquals(once.extras.toString(), twice.extras.toString())
    }

    @Test
    fun `feedback carve-out keeps bookTitle and chapterLabel only on feedback events`() {
        val feedback =
            SentryEvent().apply {
                extras = mapOf("bookTitle" to "Title", "chapterLabel" to "Chapter 1")
                contexts.put("feedback", io.sentry.protocol.Feedback("message"))
            }
        val kept = SentryPiiScrubber.scrubEvent(feedback)
        assertEquals("Title", kept.extras?.get("bookTitle"))

        val normal =
            SentryEvent().apply {
                extras = mapOf("bookTitle" to "Title")
            }
        val stripped = SentryPiiScrubber.scrubEvent(normal)
        assertFalse(stripped.extras?.containsKey("bookTitle") ?: false)
    }

    @Test
    fun `non-allowlisted breadcrumbs are dropped`() {
        val nav =
            Breadcrumb().apply {
                category = "navigation"
                message = "navigate to screen"
            }
        assertNull(SentryPiiScrubber.filterBreadcrumb(nav))

        val network =
            Breadcrumb().apply {
                category = "network.http"
                message = "GET https://example.com"
            }
        assertNull(SentryPiiScrubber.filterBreadcrumb(network))

        val noMessage = Breadcrumb().apply { category = "app" }
        assertNull(SentryPiiScrubber.filterBreadcrumb(noMessage))
    }

    @Test
    fun `allowlisted breadcrumbs pass and have their data scrubbed`() {
        val crumb =
            Breadcrumb().apply {
                category = "perf"
                message = "metric.reader_open"
                setData("bookId", "book-123")
                setData("platform", "android")
            }
        val out = SentryPiiScrubber.filterBreadcrumb(crumb)
        assertNotNull(out)
        assertEquals("[Redacted]", out!!.data["bookId"])
        assertEquals("android", out.data["platform"])

        val progress =
            Breadcrumb().apply {
                category = "navigation"
                message = "progress.emit bookId=book-1 percentage=42 source=reader"
            }
        assertNotNull(SentryPiiScrubber.filterBreadcrumb(progress))
    }

    @Test
    fun `string message redaction covers query params and loopback`() {
        val out =
            SentryPiiScrubber.redactStringMessage(
                "auth callback https://x/auth?access_token=zzz&state=sss from 127.0.0.1:5173",
            )
        assertFalse(out.contains("zzz"))
        assertFalse(out.contains("sss"))
        assertFalse(out.contains("5173"))
    }
}
