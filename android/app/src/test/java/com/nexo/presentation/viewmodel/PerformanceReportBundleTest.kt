package com.nexo.presentation.viewmodel

import com.nexo.debug.DebugLog
import com.nexo.debug.LogRedaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * G3: the diagnostics bundle must carry logs + crashes, report HONEST
 * diagnostics (null, never fake zeros), cap the log tail at 200 lines, and
 * never leak a secret or an absolute path into any entry.
 */
class PerformanceReportBundleTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val buildInfo =
        PerformanceReportBundle.BuildInfo(
            model = "Pixel",
            manufacturer = "Google",
            sdk = 34,
            appVersion = "1.2.3",
            versionCode = 42,
            gitSha = "abc123def456",
            buildTime = "2026-01-01 00:00:00",
        )

    private fun readEntries(zip: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                out[entry.name] = zf.getInputStream(entry).bufferedReader().readText()
            }
        }
        return out
    }

    private fun buildZip(
        events: List<DebugLog.DebugEvent>,
        diagnostics: PerformanceDiagnostics?,
    ): Map<String, String> {
        val zip = File(tempFolder.root, "nexo_report.zip")
        val entries =
            linkedMapOf(
                "device.txt" to PerformanceReportBundle.deviceTxt(buildInfo, "20260101_000000"),
                "timings.json" to
                    PerformanceReportBundle.timingsJson(
                        listOf(
                            PerformanceTiming(
                                key = "cold_start",
                                labelFallback = "Cold start",
                                avgMs = 2000,
                                p50Ms = 1000,
                                p95Ms = 4000,
                                maxMs = 8000,
                                count = 3,
                                samples = emptyList(),
                                fromPreviousSession = true,
                            ),
                        ),
                    ),
                "resources.json" to "{ \"db\": \"1 KB\" }",
                "sync.json" to "{ \"realtime\": false }",
                "diagnostics.json" to PerformanceReportBundle.diagnosticsJson(diagnostics),
                "logs.txt" to PerformanceReportBundle.logsTxt(events),
                "crashes.txt" to PerformanceReportBundle.crashesTxt(emptyList()),
            )
        PerformanceReportBundle.writeZip(zip, entries)
        return readEntries(zip)
    }

    @Test
    fun `zip contains logs crashes and the existing entries`() {
        val entries = buildZip(emptyList(), null)
        assertEquals(
            setOf("device.txt", "timings.json", "resources.json", "sync.json", "diagnostics.json", "logs.txt", "crashes.txt"),
            entries.keys,
        )
    }

    @Test
    fun `device entry carries app version and build identity`() {
        val device = buildZip(emptyList(), null).getValue("device.txt")
        assertTrue(device.contains("appVersion=1.2.3"))
        assertTrue(device.contains("versionCode=42"))
        assertTrue(device.contains("gitSha=abc123def456"))
        assertTrue(device.contains("buildTime=2026-01-01 00:00:00"))
        // No stable install id exists — the bundle says so instead of inventing one.
        assertTrue(device.contains("installId=(none)"))
    }

    @Test
    fun `diagnostics reports honest nulls instead of fake zeros`() {
        val diagnostics =
            buildZip(
                emptyList(),
                PerformanceDiagnostics(fpsScroll = null, fpsLabel = "n/d", anrCount = null, crashes = emptyList()),
            ).getValue("diagnostics.json")
        assertTrue("fps must be null when unmeasured", diagnostics.contains("\"fps\": null"))
        assertTrue("anrs must be null when unmeasured", diagnostics.contains("\"anrs\": null"))
        assertTrue(diagnostics.contains("\"fpsMeasured\": false"))
        assertTrue(diagnostics.contains("\"anrsMeasured\": false"))
    }

    @Test
    fun `logs tail is capped at 200 lines`() {
        val events =
            (1..250).map {
                DebugLog.DebugEvent(
                    timestamp = it.toLong(),
                    level = DebugLog.Level.INFO,
                    tag = "T",
                    message = "line $it",
                )
            }
        val logs = buildZip(events, null).getValue("logs.txt")
        assertEquals(200, logs.trim().lines().size)
        // Newest-first: the ring is already newest-first, take() keeps the head.
        assertTrue(logs.trim().lines().first().contains("line 1"))
        assertTrue(logs.trim().lines().last().contains("line 200"))
    }

    @Test
    fun `planted secret and windows path never appear in any entry`() {
        val secretFixture =
            "api_key:live_secret_123 token:tok_abc C:\\Users\\juan\\books\\novel.epub"
        // Simulate the ring holding an ALREADY-redacted message plus a raw one
        // that bypassed the boundary (the defense-in-depth case).
        val events =
            listOf(
                DebugLog.DebugEvent(1L, DebugLog.Level.INFO, "Safe", LogRedaction.redactString(secretFixture)),
                DebugLog.DebugEvent(2L, DebugLog.Level.INFO, "Leak", secretFixture),
            )
        val entries = buildZip(events, null)
        for ((name, content) in entries) {
            for (raw in listOf("live_secret_123", "tok_abc", "novel.epub", "Users\\juan", "C:\\Users")) {
                assertFalse("raw value '$raw' leaked into $name", content.contains(raw))
            }
        }
        assertTrue(entries.getValue("logs.txt").contains(LogRedaction.REDACTED))
    }

    @Test
    fun `timings entry is aggregates-only with a source marker`() {
        val timings = buildZip(emptyList(), null).getValue("timings.json")
        assertTrue(timings.contains("\"p50\": 1000"))
        assertTrue(timings.contains("\"p95\": 4000"))
        assertTrue(timings.contains("\"count\": 3"))
        assertTrue(timings.contains("\"source\": \"previous_session\""))
        assertFalse("exact-duration markers must not exist", timings.contains("durationMs"))
    }
}
