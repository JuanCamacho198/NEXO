package com.nexo.data.remote.catalog

import com.nexo.debug.DebugDual

/** Tag for every discover-rail diagnostic line; `adb logcat -s NexoRail`. */
const val CATALOG_RAIL_LOG_TAG = "NexoRail"

/**
 * Debug-level diagnostics for the Discover rails, on BOTH sides of the wire:
 * what a datasource actually requested and got back ([responded]), and why a
 * rail ended up visible or hidden ([hidden] / [loaded]).
 *
 * Rails fail closed to `Hidden`, and a hidden rail composes nothing — so without
 * this log an empty page and a swallowed exception are indistinguishable from
 * the UI, which is exactly the failure that went undiagnosed. Every line is
 * DEBUG via [DebugDual], which no-ops in JVM unit tests where `android.util.Log`
 * is not mocked.
 */
object CatalogRailLog {
    /**
     * Upstream answered. [parsedCount] is the raw `results` array length and
     * [mappedCount] what survived the mapper plus the page-size slice; a
     * non-zero parsed count with a zero mapped count means the mapper dropped
     * every item, which is a different bug from an empty page.
     */
    fun responded(
        rail: String,
        query: String,
        status: Int,
        parsedCount: Int,
        mappedCount: Int,
    ) {
        DebugDual.d(CATALOG_RAIL_LOG_TAG, "rail=$rail query=$query status=$status parsed=$parsedCount mapped=$mappedCount")
    }

    /** A rail that will compose nothing, with the reason it resolved that way. */
    fun hidden(
        rail: String,
        source: String,
        term: String?,
        reason: String,
    ) {
        DebugDual.d(CATALOG_RAIL_LOG_TAG, "rail=$rail source=$source term=${term ?: "-"} hidden reason=$reason")
    }

    /** A rail that will compose a section. */
    fun loaded(
        rail: String,
        source: String,
        term: String?,
        count: Int,
    ) {
        DebugDual.d(CATALOG_RAIL_LOG_TAG, "rail=$rail source=$source term=${term ?: "-"} loaded books=$count")
    }
}
