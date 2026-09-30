package com.nexo.debug

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.sentry.Breadcrumb
import io.sentry.ScopeCallback
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.protocol.SentryId
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * PR1 signal — DebugDual breadcrumb allowlist (task 1.6). The 5 allowlisted
 * events forward ids-only `Sentry.addBreadcrumb`; noisy events MUST NOT.
 * Uninit SDK MUST be a safe no-op with the local entry retained.
 */
class DebugDualBreadcrumbTest {
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
        mockkStatic(Sentry::class)
        every { Sentry.addBreadcrumb(any<Breadcrumb>()) } answers { }
        every { Sentry.captureException(any<Throwable>(), any<ScopeCallback>()) } returns SentryId.EMPTY_ID
        DebugLog.resetForTest()
        DebugLog.clear()
    }

    @After
    fun tearDown() {
        DebugLog.resetForTest()
        unmockkAll()
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
    }

    private fun capturedCrumbs(): List<Breadcrumb> {
        val crumbs = mutableListOf<Breadcrumb>()
        verify { Sentry.addBreadcrumb(capture(crumbs)) }
        return crumbs
    }

    @Test
    fun `allowlisted events forward ids-only breadcrumbs`() {
        DebugDual.log(DebugEvent.ProgressEmit("book-1", 42f, "reader"))
        DebugDual.log(DebugEvent.ChapterResolved("OEBPS/ch1.xhtml", "Chapter One", 2))
        DebugDual.log(DebugEvent.HighlightsApplied("hl-1", "epubcfi(/6/2)", false))
        DebugDual.log(DebugEvent.SyncOutboxFailed("HIGHLIGHT", "entity-1", "boom"))
        DebugDual.log(DebugEvent.SyncReceive("hl-9", null, true))

        val capturedAll = capturedCrumbs()
        // SAGP instruments Log.e bytecode -> SentryLogcatAdapter -> extra
        // Logcat crumbs; DebugDual.e() emits two Log.e calls, so tolerate them.
        val captured = capturedAll.filter { it.category != "Logcat" }
        assertTrue("crumbs=${captured.map { it.category to it.data.keys }}", captured.size == 5)
        assertTrue(captured.all { it.level == SentryLevel.INFO })
        assertEquals(setOf("navigation", "highlight", "sync"), captured.map { it.category }.toSet())

        val progress = captured.first { it.data["bookId"] == "book-1" }
        assertEquals(setOf("bookId", "source"), progress.data.keys)
        val chapter = captured.first { it.data["locatorHref"] != null }
        assertEquals(setOf("locatorHref", "index"), chapter.data.keys)
        assertFalse(chapter.data.containsKey("chapterTitle"))
        assertEquals(setOf("highlightId", "viaFallback"), captured.first { it.category == "highlight" }.data.keys)
        val failed = captured.first { it.data["entityType"] != null }
        assertEquals(setOf("entityType", "entityId"), failed.data.keys)
        assertFalse(failed.data.containsKey("error"))
        assertEquals(setOf("highlightId"), captured.first { it.data["highlightId"] == "hl-9" }.data.keys)
    }

    @Test
    fun `noisy events do not breadcrumb`() {
        DebugDual.log(DebugEvent.ChromeToggled(true))
        DebugDual.log(DebugEvent.FooterRecompute(800, 600, 16f, 1.5f, 16f, 1200, 300, "positions"))
        DebugDual.log(DebugEvent.ProgressReconciled("book-1", "local", 1L, 2L, 10f, 20f))

        verify(exactly = 0) { Sentry.addBreadcrumb(any<Breadcrumb>()) }
    }

    @Test
    fun `uninit SDK is a safe no-op`() {
        unmockkAll() // drop the Sentry mock: SDK uninit + runCatching → no throw
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0

        DebugDual.log(DebugEvent.ProgressEmit("book-1", 10f, "reader"))

        assertTrue(DebugLog.events.value.any { it.message.contains("progress.emit") })
    }

    @Test
    fun `PR6 - perf crumbs from P0 instrumentation are forwarded and allowlisted`() {
        DebugDual.addPerfCrumb("app_cold_start", mapOf("platform" to "android"))
        DebugDual.addPerfCrumb("reader_open", mapOf("format" to "epub"))

        val captured = capturedCrumbs().filter { it.category != "Logcat" }
        val perf = captured.filter { it.category == "perf" }
        assertEquals(2, perf.size)
        assertTrue(perf.all { it.message?.startsWith("metric.") == true })
    }
}
