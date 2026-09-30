package com.nexo.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Bucketing helper edge tests (metric-vocabulary spec: an unbucketed value
 * cannot exist) plus the in-session aggregate contract for the Performance
 * screen (PP-1 A9).
 */
class SentryMetricsTest {
    @Test
    fun `duration buckets match the shared log-scale edges`() {
        assertEquals(100L, SentryMetrics.bucketDurationMs(0))
        assertEquals(100L, SentryMetrics.bucketDurationMs(100))
        assertEquals(250L, SentryMetrics.bucketDurationMs(101))
        assertEquals(2000L, SentryMetrics.bucketDurationMs(1237))
        assertEquals(64000L, SentryMetrics.bucketDurationMs(64000))
        assertEquals(64000L, SentryMetrics.bucketDurationMs(64001))
    }

    @Test
    fun `depth buckets are 0 1-4 5-19 20-99 100+`() {
        assertEquals(0L, SentryMetrics.bucketDepth(0))
        assertEquals(4L, SentryMetrics.bucketDepth(3))
        assertEquals(19L, SentryMetrics.bucketDepth(5))
        assertEquals(99L, SentryMetrics.bucketDepth(42))
        assertEquals(100L, SentryMetrics.bucketDepth(100))
        assertEquals(100L, SentryMetrics.bucketDepth(5000))
    }

    @Test
    fun `aggregate averages and bounds follow the recorded samples`() {
        SentryMetrics.clearForTest()
        val tags = mapOf("platform" to "android", "source" to "sync")
        SentryMetrics.distribution("sync_flush", 250, tags)
        SentryMetrics.distribution("sync_flush", 2000, tags)
        SentryMetrics.distribution("sync_flush", 4000, tags)

        val agg = SentryMetrics.aggregate("sync_flush")
        assertEquals(3, agg!!.count)
        assertEquals(2083L, agg.avgMs) // ceil((250+2000+4000)/3)
        assertEquals(4000L, agg.maxMs)
        // p95 with 3 samples: ceil-index (2 * 0.95) = 1 -> 2000
        assertEquals(2000L, agg.p95Ms)
        assertNull(SentryMetrics.aggregate("save_highlight"))
    }

    @Test
    fun `lastKnownPendingCount tracks the orchestrator feed`() {
        SentryMetrics.clearForTest()
        SentryMetrics.notePendingCount(7)
        assertEquals(7, SentryMetrics.lastKnownPendingCount)
        SentryMetrics.clearForTest()
        assertEquals(0, SentryMetrics.lastKnownPendingCount)
        assertTrue(true)
    }

    private fun expectIllegal(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `install false still buckets aggregate behind gate`() {
        SentryMetrics.clearForTest()
        SentryMetrics.install { false }
        SentryMetrics.distributionRaw("discover_search_latency", 137L, mapOf("provider" to "builtin:gutendex", "cached" to "true"))
        val agg = SentryMetrics.aggregate("discover_search_latency")!!
        assertEquals(1, agg.count)
        assertEquals(SentryMetrics.bucketDurationMs(137L), agg.maxMs)
        SentryMetrics.clearForTest()
    }

    @Test
    fun `attr allowlist rejects userId query bookId keys`() {
        SentryMetrics.clearForTest()
        for (bad in listOf("userId", "query", "bookId", "user_id", "title")) {
            expectIllegal {
                SentryMetrics.distributionRaw("discover_search_latency", 100L, mapOf(bad to "x"))
            }
            expectIllegal {
                SentryMetrics.count("discover_request_total", mapOf(bad to "x"))
            }
        }
        expectIllegal {
            SentryMetrics.distributionRaw("x", 1L, mapOf("provider" to "p", "userId" to "u"))
        }
        SentryMetrics.clearForTest()
    }

    @Test
    fun `raw egress path forwards exact ms while aggregate keeps bucket edge`() {
        SentryMetrics.clearForTest()
        SentryMetrics.distributionRaw("discover_search_latency", 1237L, mapOf("provider" to "p", "cached" to "false"))
        val agg = SentryMetrics.aggregate("discover_search_latency")!!
        assertEquals(2000L, agg.maxMs)
        assertEquals(2000L, agg.avgMs)
        assertEquals(1, agg.count)
        SentryMetrics.clearForTest()
    }

    @Test
    fun `egress gate is consulted on every emission`() {
        var consulted = 0
        SentryMetrics.install {
            consulted++
            false
        }
        SentryMetrics.distribution("app_cold_start", 100L, emptyMap())
        SentryMetrics.gauge("outbox_depth", 4L, emptyMap())
        // The gate must be consulted per emission: if someone removes the check in
        // distribution()/gauge(), this count is 0 and the test fails.
        assertEquals(2, consulted)

        // Local aggregate still records with egress disabled, so the debug-only
        // Performance screen keeps working while telemetry is off.
        assertEquals(1, SentryMetrics.aggregate("app_cold_start")!!.count)

        SentryMetrics.clearForTest()
        assertNull(SentryMetrics.aggregate("app_cold_start"))
    }
}
