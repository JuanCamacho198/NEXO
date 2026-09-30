package com.nexo.debug

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.sentry.Breadcrumb
import io.sentry.ScopeCallback
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.protocol.SentryId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class DebugLogWiringTest {
    private lateinit var fakeWriter: FakeLogWriter
    private lateinit var scope: CoroutineScope

    companion object {
        @BeforeClass
        @JvmStatic
        fun mockAndroidLog() {
            mockkStatic(Log::class)
            every { Log.println(any(), any(), any()) } returns 0
        }

        @AfterClass
        @JvmStatic
        fun unmockAndroidLog() = unmockkAll()
    }

    @Before
    fun setUp() {
        fakeWriter = FakeLogWriter()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        DebugLog.resetForTest()
        DebugLog.clear() // clear in-memory events from previous tests
    }

    @After
    fun tearDown() {
        DebugLog.resetForTest()
        unmockkAll()
        // Re-mock Log for next test — @BeforeClass runs only once per class,
        // but unmockkAll clears it after each test.
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
    }

    @Test
    fun `log calls writer write when initialized`() {
        DebugLog.init(scope, fakeWriter)
        DebugLog.info("TestTag", "test message")

        // Dispatchers.Unconfined executes the launched coroutine synchronously
        assertEquals("Writer should have received 1 event", 1, fakeWriter.written.size)
        val line = fakeWriter.written.first()
        assertTrue("Writer event should contain the tag", line.contains("TestTag"))
        assertTrue("Writer event should contain the message", line.contains("test message"))
    }

    @Test
    fun `log does not throw when writer is null`() {
        // DebugLog.resetForTest() already called in setUp — writer and scope are null
        DebugLog.info("TestTag", "this should not crash")
        val events = DebugLog.events.value
        assertTrue("In-memory events should still work without writer", events.isNotEmpty())
        assertTrue("Event should contain the message", events.any { it.message.contains("should not crash") })
    }

    @Test
    fun `log preserves existing in-memory behavior`() {
        DebugLog.init(scope, fakeWriter)
        DebugLog.info("TestTag", "mem message")
        DebugLog.warn("TestTag2", "warn message")

        val events = DebugLog.events.value
        assertEquals("Should have 2 events in StateFlow", 2, events.size)
        // Events are stored newest-first — warn was called last
        assertEquals("First (newest) event should be WARN", DebugLog.Level.WARN, events[0].level)
        assertEquals("Second event should be INFO", DebugLog.Level.INFO, events[1].level)
    }

    @Test
    fun `log does not crash when writer throws`() {
        fakeWriter.failWrites = true
        DebugLog.init(scope, fakeWriter)
        DebugLog.error("TestTag", "this should not crash even if writer fails")

        val events = DebugLog.events.value
        assertTrue("In-memory events should still work when writer throws", events.isNotEmpty())
        assertTrue("Event content should be preserved", events.any { it.message.contains("writer fails") })
        // Writer threw before adding to written list
        assertTrue("Writer should have 0 successful writes", fakeWriter.written.isEmpty())
    }

    @Test
    fun `init sets writer and scope correctly`() {
        DebugLog.init(scope, fakeWriter)

        // Verify by calling log() and checking the writer received the event
        DebugLog.info("InitTest", "after init")

        assertNotNull("FakeLogWriter should have received events", fakeWriter.written)
        assertEquals(1, fakeWriter.written.size)
        assertTrue(fakeWriter.written.first().contains("InitTest"))
    }

    // PR 3 sentry-cross-platform: DebugLog.error must forward to Sentry.captureMessage.
    // Static-mocks Sentry via mockkStatic; the @After tearDown calls unmockkAll() per
    // android/AGENTS.md's "every mockkStatic needs an unmockkAll()" rule.
    @Test
    fun `error forwards to Sentry captureMessage when initialized`() {
        mockkStatic(Sentry::class)
        every { Sentry.captureMessage(any<String>(), any<SentryLevel>()) } returns mockk(relaxed = true)

        DebugLog.init(scope, fakeWriter)
        DebugLog.error("TestTag", "boom")

        // Verify Sentry.captureMessage was called with our message + ERROR level
        verify(exactly = 1) {
            Sentry.captureMessage("boom", SentryLevel.ERROR)
        }
        // Sanity: local persistence path still works
        assertTrue("Writer should still receive ERROR event", fakeWriter.written.any { it.contains("ERROR") && it.contains("boom") })
    }

    @Test
    fun `info does not invoke Sentry captureMessage`() {
        mockkStatic(Sentry::class)
        every { Sentry.captureMessage(any<String>(), any<SentryLevel>()) } returns mockk(relaxed = true)

        DebugLog.init(scope, fakeWriter)
        DebugLog.info("TestTag", "no boom here")

        // No ERROR → Sentry.captureMessage must not be called (only ERROR forwards)
        verify(exactly = 0) {
            Sentry.captureMessage(any<String>(), any<SentryLevel>())
        }
    }

    // FIX 5: typed reader/sync telemetry must NOT create Sentry error issues.
    // Previously each event fired Sentry.captureException(RuntimeException(msg));
    // now they are local WARN/DEBUG entries plus ids-only breadcrumbs, so these
    // tests pin "no captureException" for every telemetry event.
    private fun assertTelemetryDoesNotCaptureException(event: DebugEvent) {
        mockkStatic(Sentry::class)
        every { Sentry.captureException(any<Throwable>(), any<ScopeCallback>()) } returns SentryId.EMPTY_ID
        every { Sentry.addBreadcrumb(any<Breadcrumb>()) } answers { }

        DebugDual.log(event)

        verify(exactly = 0) { Sentry.captureException(any<Throwable>(), any<ScopeCallback>()) }
    }

    @Test
    fun `highlightsSkipped emits telemetry without a Sentry exception`() {
        assertTelemetryDoesNotCaptureException(
            DebugEvent.HighlightsSkipped("hl1", "epubcfi(/6/2)", "bounds_out_of_viewport"),
        )

        // Local source of truth preserved: WARN entry still lands in DebugLog
        assertTrue(
            "DebugLog should still hold the local entry",
            DebugLog.events.value.any { it.message.contains("highlights.skipped") },
        )
    }

    @Test
    fun `highlightsApplied emits telemetry without a Sentry exception`() {
        assertTelemetryDoesNotCaptureException(
            DebugEvent.HighlightsApplied("hl2", "epubcfi(/6/4)", true),
        )
        assertTrue(DebugLog.events.value.any { it.message.contains("highlights.applied") })
    }

    @Test
    fun `syncOutboxFailed emits telemetry without a Sentry exception`() {
        assertTelemetryDoesNotCaptureException(
            DebugEvent.SyncOutboxFailed("HIGHLIGHT", "hl1", "x".repeat(250)),
        )
        assertTrue(DebugLog.events.value.any { it.message.contains("sync.outboxFailed") })
    }

    @Test
    fun `footerMismatch emits telemetry without a Sentry exception`() {
        assertTelemetryDoesNotCaptureException(
            DebugEvent.FooterMismatch("ch1.html", "Chapter One", "Chapter Two"),
        )
        assertTrue(DebugLog.events.value.any { it.message.contains("reader.footerMismatch") })
    }

    @Test
    fun `syncReceive emits telemetry without a Sentry exception`() {
        assertTelemetryDoesNotCaptureException(
            DebugEvent.SyncReceive("hl3", "epubcfi(/6/6)", true),
        )
        assertTrue(DebugLog.events.value.any { it.message.contains("sync.receive") })
    }

    @Test
    fun `chapterResolved emits telemetry without a Sentry exception`() {
        assertTelemetryDoesNotCaptureException(
            DebugEvent.ChapterResolved("ch2.html", "Chapter Two", 3),
        )
        assertTrue(DebugLog.events.value.any { it.message.contains("footer.chapterResolved") })
    }
}
