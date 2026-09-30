package com.nexo.debug

import io.sentry.Sentry
import io.sentry.SentryAttributes
import io.sentry.metrics.SentryMetricsParameters
import java.util.Collections

/**
 * P0 metric emission (metrics-egress spec): Android emits numeric metrics
 * directly via the Sentry metrics API. Every numeric value is BUCKETED here,
 * at the instrumentation site, before egress — an unbucketed value cannot
 * exist. All emissions are wrapped in runCatching: an uninit SDK (no DSN) is
 * a safe no-op.
 *
 * Also keeps in-session aggregates for the debug-only Performance screen
 * (PP-1 RealPerformanceDataSource). Aggregates live in memory only.
 */
object SentryMetrics {
    /** In-session per-key timing samples (ms, exact values — local debug UI only). */
    data class Aggregate(
        val avgMs: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val samples: List<Float>,
        val count: Int,
    )

    private const val MAX_SAMPLES = 64
    private const val P95_INDEX_FACTOR = 0.95

    private val samplesByKey =
        Collections.synchronizedMap(
            mutableMapOf<String, MutableList<Long>>(),
        )

    /**
     * PP-3 opt-out gate, consulted on EVERY emission.
     *
     * Sentry metric envelope items bypass `beforeSend`/`beforeBreadcrumb`, so a
     * veto installed only in those callbacks never sees them. Mirrors the desktop
     * SentryMetricsSink per-emit check. Defaults to enabled; the app installs the
     * real supplier in onCreate.
     */
    @Volatile
    private var egressEnabled: () -> Boolean = { true }

    fun install(isEgressEnabled: () -> Boolean) {
        egressEnabled = isEgressEnabled
    }

    @Volatile
    var lastKnownPendingCount: Int = 0
        private set

    fun notePendingCount(count: Int) {
        lastKnownPendingCount = count
    }

    fun clearForTest() {
        synchronized(samplesByKey) { samplesByKey.clear() }
        lastKnownPendingCount = 0
        egressEnabled = { true }
    }

    /** Log-scale duration buckets (ms) — mirrors metricBuckets.ts. */
    val DURATION_BUCKETS_MS: LongArray =
        longArrayOf(
            100L,
            250L,
            500L,
            1000L,
            2000L,
            4000L,
            8000L,
            16000L,
            32000L,
            64000L,
        )

    fun bucketDurationMs(ms: Long): Long {
        for (bound in DURATION_BUCKETS_MS) {
            if (ms <= bound) return bound
        }
        // Overflow bucket: emit the last finite edge, matching bucketDepth and
        // bucketSizeBytes. A negative sentinel would reach Sentry as a negative
        // latency.
        return DURATION_BUCKETS_MS.last()
    }

    /** Outbox depth bucket edges (upper bound emitted): 0, 1-4, 5-19, 20-99, 100+. */
    private const val OUTBOX_DEPTH_SMALL_MAX = 4
    private const val OUTBOX_DEPTH_MEDIUM_MAX = 19
    private const val OUTBOX_DEPTH_LARGE_MAX = 99

    /** Outbox depth buckets: 0, 1-4, 5-19, 20-99, 100+ (upper bound emitted). */
    fun bucketDepth(count: Int): Long =
        when {
            count <= 0 -> 0L
            count <= OUTBOX_DEPTH_SMALL_MAX -> OUTBOX_DEPTH_SMALL_MAX.toLong()
            count <= OUTBOX_DEPTH_MEDIUM_MAX -> OUTBOX_DEPTH_MEDIUM_MAX.toLong()
            count <= OUTBOX_DEPTH_LARGE_MAX -> OUTBOX_DEPTH_LARGE_MAX.toLong()
            else -> 100L
        }

    private fun metricParams(tags: Map<String, String>): SentryMetricsParameters = SentryMetricsParameters.create(SentryAttributes.fromMap(tags))

    fun distribution(
        name: String,
        bucketedValue: Long,
        tags: Map<String, String>,
    ) {
        if (egressEnabled()) {
            runCatching {
                Sentry.metrics().distribution(
                    name,
                    bucketedValue.toDouble(),
                    "millisecond",
                    metricParams(tags),
                )
            }
        }
        // Local aggregate and breadcrumb still run when disabled: neither is egress
        // on this path (breadcrumbs are vetoed by beforeBreadcrumb).
        recordSample(name, bucketedValue)
        DebugDual.addPerfCrumb(name, tags)
    }

    fun gauge(
        name: String,
        bucketedValue: Long,
        tags: Map<String, String>,
    ) {
        if (egressEnabled()) {
            runCatching {
                Sentry.metrics().gauge(
                    name,
                    bucketedValue.toDouble(),
                    null,
                    metricParams(tags),
                )
            }
        }
        recordSample(name, bucketedValue)
        DebugDual.addPerfCrumb(name, tags)
    }

    /**
     * Bounded attribute keys for Discover Application Metrics (U3).
     * Only these keys may egress; user IDs, query text, and book IDs
     * MUST NEVER appear here. Legacy sync/outbox metrics keep their own
     * keys and stay outside this allowlist.
     */
    val DISCOVER_ATTR_KEYS: Set<String> = setOf("provider", "code", "surface", "cached")

    private fun requireAttrKeys(tags: Map<String, String>) {
        require(tags.keys.all { it in DISCOVER_ATTR_KEYS }) {
            "Discover metrics carry bounded attrs only " +
                "{provider, code, surface, cached}; got ${tags.keys}"
        }
    }

    /**
     * Raw-value distribution (U3-1): forwards the EXACT sample to Sentry
     * so p95 is valid. The bucketed edge is recorded for the local
     * debug-only aggregate via [recordSample]; bucketed values never
     * egress on this path. MUST land before any Discover emissions.
     */
    fun distributionRaw(
        name: String,
        rawMs: Long,
        tags: Map<String, String>,
    ) {
        requireAttrKeys(tags)
        if (egressEnabled()) {
            runCatching {
                Sentry.metrics().distribution(
                    name,
                    rawMs.toDouble(),
                    "millisecond",
                    metricParams(tags),
                )
            }
        }
        // Local aggregate and breadcrumb still run when disabled: neither
        // is egress on this path (breadcrumbs are vetoed by
        // beforeBreadcrumb). The aggregate keeps the bucket edge only.
        recordSample(name, bucketDurationMs(rawMs))
        DebugDual.addPerfCrumb(name, tags)
    }

    /**
     * Bounded counter (U3-1): Discover totals, errors, empties, funnel.
     * Intent-based emission only; no timing aggregate is recorded.
     */
    fun count(
        name: String,
        tags: Map<String, String>,
        value: Long = 1L,
    ) {
        requireAttrKeys(tags)
        if (egressEnabled()) {
            runCatching {
                Sentry.metrics().count(
                    name,
                    value.toDouble(),
                    "none",
                    metricParams(tags),
                )
            }
        }
    }

    private fun recordSample(
        name: String,
        bucketedValue: Long,
    ) {
        synchronized(samplesByKey) {
            val list = samplesByKey.getOrPut(name) { mutableListOf() }
            list.add(bucketedValue)
            if (list.size > MAX_SAMPLES) list.removeAt(0)
        }
    }

    /** In-session aggregate for the Performance screen (debug builds only). */
    fun aggregate(name: String): Aggregate? {
        val list = synchronized(samplesByKey) { samplesByKey[name]?.toList() } ?: return null
        if (list.isEmpty()) return null
        val sorted = list.sorted()
        val p95Index = ((sorted.size - 1) * P95_INDEX_FACTOR).toInt().coerceIn(0, sorted.size - 1)
        return Aggregate(
            avgMs = sorted.average().toLong(),
            p95Ms = sorted[p95Index],
            maxMs = sorted.last(),
            samples = list.map { it.toFloat() },
            count = list.size,
        )
    }
}
