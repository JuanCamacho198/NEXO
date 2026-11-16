package com.nexo.di

import com.nexo.debug.SentryMetrics
import com.nexo.domain.telemetry.TelemetrySink

/**
 * Adapts the Android Sentry-backed [SentryMetrics] to the pure-JVM
 * [TelemetrySink] port, so `:core:domain` use cases can emit metrics without
 * depending on the Sentry Android SDK.
 *
 * Pure delegation: bucketing stays in [SentryMetrics] (single source of truth
 * for the bucket edges) and every emission keeps its egress gate, local
 * aggregate, and perf breadcrumb.
 */
object SentryTelemetrySink : TelemetrySink {
    override fun bucketDurationMs(ms: Long): Long = SentryMetrics.bucketDurationMs(ms)

    override fun distribution(
        name: String,
        bucketedValue: Long,
        tags: Map<String, String>,
    ) = SentryMetrics.distribution(name, bucketedValue, tags)
}
