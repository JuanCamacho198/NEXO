package com.nexo.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * G4: bucketed timing aggregates must survive a process restart, and no exact
 * per-request duration may ever reach disk.
 */
class MetricAggregateStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `p50 and p95 survive a simulated restart`() {
        val file = tempFolder.newFile("metric_aggregates.json")
        val tags = mapOf("platform" to "android", "source" to "app_shell")
        fun bucket(ms: Long) = SentryMetrics.bucketDurationMs(ms)

        // ── Process 1: record then flush via the installed store. ──
        SentryMetrics.clearForTest()
        SentryMetrics.install { false }
        SentryMetrics.installPersistence(MetricAggregateStore(file))
        SentryMetrics.distribution("app_cold_start", bucket(500), tags)
        SentryMetrics.distribution("app_cold_start", bucket(2000), tags)
        SentryMetrics.distribution("app_cold_start", bucket(4000), tags)

        // ── Process 2: fresh in-memory state, same file on disk. ──
        SentryMetrics.clearForTest()
        SentryMetrics.install { false }
        SentryMetrics.installPersistence(MetricAggregateStore(file))

        val reloaded = SentryMetrics.persistedAggregate("app_cold_start")
        assertNotNull("persisted aggregate must reload after a restart", reloaded)
        // Sorted bucket edges [500, 2000, 4000]: p50 index (2*0.5)=1 -> 2000;
        // p95 index (2*0.95)=1 -> 2000.
        assertEquals(2000L, reloaded!!.p50Ms)
        assertEquals(2000L, reloaded.p95Ms)
        assertEquals(4000L, reloaded.maxMs)
        assertEquals(3, reloaded.count)

        SentryMetrics.clearForTest()
    }

    @Test
    fun `no exact duration is present in the persisted artifact`() {
        val file = tempFolder.newFile("metric_aggregates.json")
        val tags = mapOf("platform" to "android", "source" to "app_shell")

        SentryMetrics.clearForTest()
        SentryMetrics.install { false }
        SentryMetrics.installPersistence(MetricAggregateStore(file))
        // A caller measures 1237 ms and buckets it before recording (the real
        // call-site contract, see MainActivity app_cold_start). 1237 is not a
        // bucket edge (edges: 100,250,500,1000,2000,4000,...).
        val elapsedMs = 1237L
        SentryMetrics.distribution("reader_open", SentryMetrics.bucketDurationMs(elapsedMs), tags)

        val raw = file.readText()
        assertFalse("exact duration leaked into the persisted artifact", raw.contains("1237"))
        // Only the bucket edge may appear.
        assertTrue("bucket edge expected", raw.contains(SentryMetrics.bucketDurationMs(elapsedMs).toString()))

        SentryMetrics.clearForTest()
    }

    @Test
    fun `a fresh session's samples win over the persisted aggregate`() {
        val file = tempFolder.newFile("metric_aggregates.json")
        val tags = mapOf("platform" to "android", "source" to "app_shell")
        fun bucket(ms: Long) = SentryMetrics.bucketDurationMs(ms)

        SentryMetrics.clearForTest()
        SentryMetrics.install { false }
        SentryMetrics.installPersistence(MetricAggregateStore(file))
        SentryMetrics.distribution("sync_flush", bucket(250), tags)

        SentryMetrics.clearForTest()
        SentryMetrics.install { false }
        SentryMetrics.installPersistence(MetricAggregateStore(file))

        // New session has no in-session samples yet: persisted aggregate shows.
        assertEquals(1, SentryMetrics.persistedAggregate("sync_flush")!!.count)

        // Once this session records, the in-session aggregate takes precedence;
        // persistedAggregate is only consulted as a fallback by the data source.
        SentryMetrics.distribution("sync_flush", bucket(4000), tags)
        assertEquals(1, SentryMetrics.aggregate("sync_flush")!!.count)
        assertEquals(4000L, SentryMetrics.aggregate("sync_flush")!!.maxMs)

        SentryMetrics.clearForTest()
    }

    @Test
    fun `store round-trips aggregates and tolerates corruption`() {
        val file = tempFolder.newFile("metric_aggregates.json")
        val store = MetricAggregateStore(file)

        store.save(
            mapOf(
                "app_cold_start" to
                    MetricAggregateStore.PersistedAggregate(
                        count = 4,
                        avgMs = 1000L,
                        p50Ms = 500L,
                        p95Ms = 4000L,
                        maxMs = 8000L,
                    ),
            ),
        )
        val loaded = store.load()
        assertEquals(4, loaded["app_cold_start"]!!.count)
        assertEquals(500L, loaded["app_cold_start"]!!.p50Ms)
        assertEquals(8000L, loaded["app_cold_start"]!!.maxMs)

        file.writeText("{ not json")
        assertTrue("corrupt file must load as empty, never throw", store.load().isEmpty())
    }

    @Test
    fun `no persistence installed keeps everything in-memory only`() {
        SentryMetrics.clearForTest()
        SentryMetrics.install { false }
        SentryMetrics.distribution("app_cold_start", 500L, emptyMap())
        assertNotNull(SentryMetrics.aggregate("app_cold_start"))
        assertNull(SentryMetrics.persistedAggregate("app_cold_start"))
        SentryMetrics.clearForTest()
    }
}
