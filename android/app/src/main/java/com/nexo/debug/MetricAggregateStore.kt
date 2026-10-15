package com.nexo.debug

import org.json.JSONObject
import java.io.File

/**
 * Persists **bucketed** timing aggregates across process restarts so p50/p95
 * survive a cold start.
 *
 * Invariant: only bucket EDGES (see [SentryMetrics.DURATION_BUCKETS_MS]) and
 * the aggregates derived from them ever reach this file. Exact per-request
 * durations never leave memory — they are bucketed by [SentryMetrics] before
 * [SentryMetrics.Aggregate] is built, and the raw sample list is intentionally
 * NOT persisted (only count/avg/p50/p95/max).
 *
 * Deliberately Android-free (takes a [File]) so it runs in a plain JVM unit
 * test and so the "no exact durations on disk" guarantee is testable.
 */
class MetricAggregateStore(
    private val file: File,
) {
    data class PersistedAggregate(
        val count: Int,
        val avgMs: Long,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
    )

    /** Best-effort read; a missing/corrupt file yields an empty map, never a throw. */
    fun load(): Map<String, PersistedAggregate> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyMap()
        if (text.isBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(text)
            val out = LinkedHashMap<String, PersistedAggregate>()
            for (name in root.keys()) {
                val o = root.optJSONObject(name) ?: continue
                if (!o.has("count") || !o.has("p50") || !o.has("p95")) continue
                out[name] =
                    PersistedAggregate(
                        count = o.optInt("count", 0),
                        avgMs = o.optLong("avg", 0L),
                        p50Ms = o.optLong("p50", 0L),
                        p95Ms = o.optLong("p95", 0L),
                        maxMs = o.optLong("max", 0L),
                    )
            }
            out
        }.getOrDefault(emptyMap())
    }

    /**
     * Atomically replaces the file with [aggregates]. Writes to a sibling temp
     * file first (mirrors `LayoutMigration.writeVersion`) so a crash mid-write
     * can never leave a half-parsed aggregate file behind.
     */
    fun save(aggregates: Map<String, PersistedAggregate>) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject()
            for ((name, agg) in aggregates) {
                root.put(
                    name,
                    JSONObject().apply {
                        put("count", agg.count)
                        put("avg", agg.avgMs)
                        put("p50", agg.p50Ms)
                        put("p95", agg.p95Ms)
                        put("max", agg.maxMs)
                    },
                )
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }
}
