package com.nexo.domain.telemetry

/**
 * Domain seam for emitting bucketed duration metrics.
 *
 * This module is a plain JVM target and must NOT depend on the Sentry Android
 * SDK, so the concrete instrumentation lives in `:app`
 * (`com.nexo.debug.SentryMetrics`) and is wired in `di/`. Signatures mirror the
 * two members that domain use cases actually call, so that implementation is a
 * drop-in adapter.
 *
 * Bucketing is performed by the call site BEFORE egress; an unbucketed value
 * never reaches this interface.
 */
interface TelemetrySink {
    /** Log-scale bucket edge (ms) for a measured duration. */
    fun bucketDurationMs(ms: Long): Long

    /**
     * Emits one bucketed duration sample. [tags] carries bounded attribute keys
     * only — never user content.
     */
    fun distribution(
        name: String,
        bucketedValue: Long,
        tags: Map<String, String>,
    )

    companion object {
        /** Log-scale duration bucket edges (ms) — mirrors metricBuckets.ts. */
        private val DURATION_BUCKETS_MS: LongArray =
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

        /**
         * Discards every emission. Default for callers and tests that do not
         * wire real telemetry, keeping the use case constructible without any
         * instrumentation dependency.
         *
         * [bucketDurationMs] still buckets (identical edges to the real sink) so
         * the "no unbucketed value exists" invariant holds regardless of wiring.
         */
        val NoOp: TelemetrySink =
            object : TelemetrySink {
                override fun bucketDurationMs(ms: Long): Long {
                    for (bound in DURATION_BUCKETS_MS) {
                        if (ms <= bound) return bound
                    }
                    // Overflow: emit the last finite edge, never a raw value.
                    return DURATION_BUCKETS_MS.last()
                }

                override fun distribution(
                    name: String,
                    bucketedValue: Long,
                    tags: Map<String, String>,
                ) = Unit
            }
    }
}
