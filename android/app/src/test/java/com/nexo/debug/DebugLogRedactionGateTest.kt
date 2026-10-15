package com.nexo.debug

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the redaction boundary + level gate inside [DebugLog].
 * Every sink (ring, toText, writer) must see the redacted form; a gated event
 * must leave no trace anywhere.
 */
class DebugLogRedactionGateTest {
    private lateinit var fakeWriter: FakeLogWriter
    private lateinit var scope: CoroutineScope

    private val secretFixture =
        "api_key:live_secret_123 token:tok_abc C:\\Users\\juan\\books\\novel.epub"

    @Before
    fun setUp() {
        fakeWriter = FakeLogWriter()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        DebugLog.resetForTest()
        DebugLog.clear()
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        DebugLog.resetForTest()
        unmockkAll()
    }

    private fun assertNoRawSecret(text: String) {
        for (raw in listOf("live_secret_123", "tok_abc", "novel.epub", "juan")) {
            assertFalse("raw value leaked into: $text", text.contains(raw))
        }
    }

    @Test
    fun `info event is redacted in ring, toText and writer`() {
        DebugLog.init(scope, fakeWriter)
        DebugLog.info("TestTag", secretFixture)

        val event = DebugLog.events.value.first()
        assertNoRawSecret(event.message)
        assertTrue(event.message.contains(LogRedaction.REDACTED))

        assertNoRawSecret(DebugLog.toText())
        assertTrue(fakeWriter.written.isNotEmpty())
        fakeWriter.written.forEach { assertNoRawSecret(it) }
    }

    @Test
    fun `stored event is redacted exactly once`() {
        DebugLog.init(scope, fakeWriter)
        DebugLog.info("TestTag", secretFixture)

        val stored =
            DebugLog.events.value
                .first()
                .message
        assertEquals(stored, LogRedaction.redactString(stored))
    }

    @Test
    fun `stored message is not double-redacted by toText`() {
        DebugLog.init(scope, fakeWriter)
        DebugLog.info("TestTag", "api_key:abc123")

        val out = DebugLog.toText()
        assertEquals("[INFO] TestTag: api_key:[REDACTED]", out.trim())
    }

    @Test
    fun `error event forwards the redacted message to Sentry`() {
        mockkStatic(Sentry::class)
        every { Sentry.captureMessage(any<String>(), any<SentryLevel>()) } returns mockk(relaxed = true)

        DebugLog.init(scope, fakeWriter)
        DebugLog.error("TestTag", secretFixture)

        verify(exactly = 1) {
            Sentry.captureMessage(
                match { !it.contains("live_secret_123") && it.contains(LogRedaction.REDACTED) },
                SentryLevel.ERROR,
            )
        }
    }

    @Test
    fun `below-threshold event produces no ring, writer or Sentry side effect`() {
        mockkStatic(Sentry::class)
        every { Sentry.captureMessage(any<String>(), any<SentryLevel>()) } returns mockk(relaxed = true)

        DebugLog.init(scope, fakeWriter)
        DebugLog.setMinLevel(DebugLog.Level.WARN)

        DebugLog.info("TestTag", "should be dropped")

        assertTrue("no ring entry expected", DebugLog.events.value.isEmpty())
        assertTrue("no writer write expected", fakeWriter.written.isEmpty())
        verify(exactly = 0) { Sentry.captureMessage(any<String>(), any<SentryLevel>()) }
    }

    @Test
    fun `at-or-above-threshold event still flows`() {
        DebugLog.init(scope, fakeWriter)
        DebugLog.setMinLevel(DebugLog.Level.WARN)

        DebugLog.warn("TestTag", "kept")
        DebugLog.error("TestTag", "kept too")

        assertEquals(2, DebugLog.events.value.size)
        assertEquals(2, fakeWriter.written.size)
    }

    @Test
    fun `debug is dropped when the threshold is INFO`() {
        DebugLog.init(scope, fakeWriter)
        DebugLog.setMinLevel(DebugLog.Level.INFO)

        DebugLog.debug("TestTag", "noisy")

        assertTrue(DebugLog.events.value.isEmpty())
        assertTrue(fakeWriter.written.isEmpty())
    }

    @Test
    fun `success is treated as INFO level for gating`() {
        DebugLog.init(scope, fakeWriter)
        DebugLog.setMinLevel(DebugLog.Level.INFO)

        DebugLog.success("TestTag", "all good")

        assertEquals(1, DebugLog.events.value.size)
        assertEquals(
            DebugLog.Level.SUCCESS,
            DebugLog.events.value
                .first()
                .level,
        )
    }

    @Test
    fun `gated event is dropped before reaching the error ring`() {
        DebugLog.init(scope, fakeWriter)
        // Threshold above ERROR would be nonsensical but must still be safe:
        // nothing at ERROR severity can exceed it, so nothing may be stored.
        DebugLog.setMinLevel(DebugLog.Level.ERROR)

        DebugLog.debug("TestTag", "dropped")
        DebugLog.info("TestTag", "dropped")

        assertTrue(DebugLog.events.value.isEmpty())
        assertTrue(DebugLog.errorEvents.value.isEmpty())
    }
}
