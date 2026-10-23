package com.nexo.data.remote.addons.protocol

import com.nexo.data.remote.addons.AddonFetchException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/** Response bound from the protocol contract: 512 KiB per payload body. */
private const val MAX_BODY_BYTES = 512 * 1024

/** Per-addon fetch budget. The transport owns the deadline; this only passes it on. */
private const val DEFAULT_TIMEOUT_MS = 8_000L

private const val DEFAULT_MAX_CACHE_ENTRIES = 500
private const val DEFAULT_BREAKER_THRESHOLD = 3
private const val DEFAULT_BREAKER_OPEN_MS = 300_000L

/** Seconds, per resource, from the protocol contract. */
private val DEFAULT_TTL_MS =
    mapOf(
        "catalog" to 300_000L,
        "meta" to 1_800_000L,
        "stream" to 600_000L,
    )

/**
 * Per-addon response cache. Pure bookkeeping, no I/O.
 *
 * Time comes from an injected `now` so ttl behavior is testable without
 * sleeping, and entries are held least-recently-used first: a `get` moves a
 * key to the back, and `set` evicts from the front at `maxEntries`.
 */
class TtlCache(
    private val now: () -> Long = System::currentTimeMillis,
    maxEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
) {
    private class Entry(
        val value: Any,
        val expiresAt: Long,
    )

    private val capacity: Int = maxOf(1, maxEntries)
    private val entries = LinkedHashMap<String, Entry>()

    /** `null` when absent or expired. A ttl boundary is already expired. */
    fun get(key: String): Any? {
        val entry = entries[key] ?: return null
        if (entry.expiresAt <= now()) {
            entries.remove(key)
            return null
        }
        // Re-inserting moves the key to the back of the map: the LRU order.
        entries.remove(key)
        entries[key] = entry
        return entry.value
    }

    /** A non-positive ttl stores nothing and drops any stale value for the key. */
    fun set(
        key: String,
        value: Any,
        ttlMs: Long,
    ) {
        entries.remove(key)
        if (ttlMs <= 0) return

        if (entries.size >= capacity) {
            purgeExpired()
            while (entries.size >= capacity) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
        entries[key] = Entry(value, now() + ttlMs)
    }

    fun clear() {
        entries.clear()
    }

    private fun purgeExpired() {
        val current = now()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.expiresAt <= current) iterator.remove()
        }
    }
}

/**
 * Per-addon circuit breaker. Pure bookkeeping, no I/O.
 *
 * A dead addon must not light up the UI on every search, so after `threshold`
 * consecutive failures the addon is skipped outright for `openMs`, then
 * half-opens for exactly one probe. A failed probe pays another full cooldown;
 * a successful one resets the addon.
 */
class CircuitBreaker(
    threshold: Int = DEFAULT_BREAKER_THRESHOLD,
    openMs: Long = DEFAULT_BREAKER_OPEN_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class State(
        var failures: Int = 0,
        var openedAt: Long? = null,
        var probed: Boolean = false,
    )

    private val failuresBeforeOpen: Int = maxOf(1, threshold)
    private val cooldownMs: Long = maxOf(0L, openMs)
    private val states = mutableMapOf<String, State>()

    fun canRequest(addonId: String): Boolean {
        val state = states[addonId] ?: return true
        val openedAt = state.openedAt ?: return true
        if (now() - openedAt < cooldownMs) return false
        if (state.probed) return false
        // Half-open: this caller owns the only probe.
        state.probed = true
        return true
    }

    fun recordSuccess(addonId: String) {
        states.remove(addonId)
    }

    fun recordFailure(addonId: String) {
        val state = states[addonId] ?: State()
        state.probed = false

        if (state.openedAt != null) {
            // The probe failed: open again for a full cooldown.
            state.openedAt = now()
            state.failures = 0
        } else {
            state.failures += 1
            if (state.failures >= failuresBeforeOpen) {
                state.openedAt = now()
                state.failures = 0
            }
        }
        states[addonId] = state
    }
}

/** The transport owns cancellation, so it must signal its own timeout. */
class AddonTransportTimeoutException(
    message: String = "addon request timed out",
) : Exception(message)

data class ResourceResponse(
    val status: Int,
    val body: Any?,
    val cacheControl: String? = null,
)

fun interface AddonResourceTransport {
    suspend fun fetch(
        url: String,
        timeoutMs: Long,
    ): ResourceResponse
}

enum class FailureReason {
    TIMEOUT,
    NETWORK,
    STATUS,
    PAYLOAD,
}

data class AddonFailure(
    val addonId: String,
    val reason: FailureReason,
)

data class FanoutResult<T>(
    val items: List<T>,
    val failures: List<AddonFailure>,
    /** Addons not attempted because their circuit is open. Never a failure. */
    val skipped: Int,
)

/** A catalog or meta payload unit plus the addon that produced it. */
data class AddonMetaItem(
    val meta: MetaPreview,
    val addonId: String,
    val addedAt: Long,
)

data class CatalogRequest(
    val catalogId: String,
    val type: String = AddonProtocolConstants.TYPE_BOOK,
    val extra: Map<String, Any?>? = null,
)

data class IdRequest(
    val id: String,
    val type: String = AddonProtocolConstants.TYPE_BOOK,
)

/** Lowercase scheme and host, no trailing slash, no fragment. */
fun normalizeUrl(raw: String): String {
    val uri =
        try {
            URI(raw)
        } catch (_: Exception) {
            return raw.trim()
        }
    val scheme = uri.scheme ?: return raw.trim()
    val host = uri.host?.lowercase() ?: ""
    val port = if (uri.port >= 0) ":${uri.port}" else ""
    val path = (uri.path ?: "").replace(Regex("/+$"), "")
    val query = uri.rawQuery?.let { "?$it" } ?: ""
    return "${scheme.lowercase()}://$host$port$path$query"
}

private val MAX_AGE = Regex("(?:^|[\\s,])max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

/** An upstream `max-age` may shorten the client ttl, never extend it. */
private fun effectiveTtlMs(
    defaultTtlMs: Long,
    cacheControl: String?,
): Long {
    if (cacheControl == null) return defaultTtlMs
    val match = MAX_AGE.find(cacheControl) ?: return defaultTtlMs
    val seconds = match.groupValues[1].toLongOrNull() ?: return defaultTtlMs
    return minOf(defaultTtlMs, seconds.coerceAtMost(Long.MAX_VALUE / MILLIS_PER_SECOND) * MILLIS_PER_SECOND)
}

private const val MILLIS_PER_SECOND = 1_000L

/** Serializes a parsed body so its size can be measured against the 512 KiB cap. */
private fun serializedBodySize(body: Any?): Int? {
    val serialized =
        when (body) {
            null -> "null"
            is JSONObject -> body.toString()
            is JSONArray -> body.toString()
            is String -> JSONObject.quote(body)
            else -> body.toString()
        }
    if (serialized.length > MAX_BODY_BYTES) return serialized.length
    return serialized.toByteArray(Charsets.UTF_8).size
}

private fun exceedsBodyCap(body: Any?): Boolean {
    val size =
        try {
            serializedBodySize(body)
        } catch (_: Exception) {
            return true
        } ?: return true
    return size > MAX_BODY_BYTES
}

/**
 * A JSON parse failure inside the transport is a payload problem; anything else
 * is treated as a network problem, and an explicit timeout signal as a timeout.
 */
private fun classifyTransportError(error: Throwable): FailureReason =
    when (error) {
        is AddonTransportTimeoutException, is TimeoutCancellationException -> FailureReason.TIMEOUT
        is JSONException, is SerializationException -> FailureReason.PAYLOAD
        else -> FailureReason.NETWORK
    }

/**
 * Isolated fan-out over every addon that can answer a request.
 *
 * One addon failing — timeout, non-2xx, malformed JSON, an oversized body, an
 * unbuildable request URL — never fails the aggregate. Addons whose circuit is
 * open are skipped silently so a dead addon cannot light up the UI on every
 * search.
 */
class AddonClient(
    private val transport: AddonResourceTransport,
    cache: TtlCache? = null,
    breaker: CircuitBreaker? = null,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    now: () -> Long = System::currentTimeMillis,
) {
    private class Job<T>(
        val addon: AddonDescriptorV3,
        val url: String,
        val cacheKey: String,
        val ttlMs: Long,
        val read: (Any?, AddonDescriptorV3) -> List<T>,
    )

    private class Attempt<T>(
        val items: List<T>?,
        val reason: FailureReason?,
        val addonId: String,
    ) {
        companion object {
            fun <T> ok(
                items: List<T>,
                addonId: String,
            ): Attempt<T> = Attempt(items, null, addonId)

            fun <T> failed(
                reason: FailureReason,
                addonId: String,
            ): Attempt<T> = Attempt(null, reason, addonId)
        }
    }

    private class FanoutSpec<T>(
        val addons: List<AddonDescriptorV3>,
        val resource: String,
        val type: String,
        val id: String,
        /** Per addon, because each addon declares its own allow-list. */
        val extraFor: (AddonManifestV3) -> Map<String, Any?>,
        val read: (Any?, AddonDescriptorV3) -> List<T>,
    )

    private val timeout: Long = timeoutMs
    private val cacheStore: TtlCache = cache ?: TtlCache(now = now)
    private val circuit: CircuitBreaker = breaker ?: CircuitBreaker(now = now)

    suspend fun searchCatalog(
        addons: List<AddonDescriptorV3>,
        request: CatalogRequest,
    ): FanoutResult<AddonMetaItem> =
        collect(
            FanoutSpec(
                addons = addons,
                resource = "catalog",
                type = request.type,
                id = request.catalogId,
                extraFor = { manifest -> declaredOnly(manifest, request.catalogId, request.extra) },
                read = ::readCatalog,
            ),
        )

    suspend fun getMeta(
        addons: List<AddonDescriptorV3>,
        request: IdRequest,
    ): FanoutResult<AddonMetaItem> =
        collect(
            FanoutSpec(
                addons = addons,
                resource = "meta",
                type = request.type,
                id = request.id,
                extraFor = { emptyMap() },
                read = ::readMeta,
            ),
        )

    suspend fun getStreams(
        addons: List<AddonDescriptorV3>,
        request: IdRequest,
    ): FanoutResult<BookStream> {
        val result =
            collect(
                FanoutSpec(
                    addons = addons,
                    resource = "stream",
                    type = request.type,
                    id = request.id,
                    extraFor = { emptyMap() },
                    read = ::readStreams,
                ),
            )
        return result.copy(items = AddonPayloads.orderStreams(result.items))
    }

    private fun shapeOf(manifest: AddonManifestV3): AddonShape = manifest.nexo?.shape ?: AddonShape.RAW

    private fun readCatalog(
        body: Any?,
        addon: AddonDescriptorV3,
    ): List<AddonMetaItem> =
        AddonPayloads
            .parseCatalogPayload(body, shapeOf(addon.manifest))
            .metas
            .map { AddonMetaItem(meta = it, addonId = addon.id, addedAt = addon.addedAt) }

    private fun readMeta(
        body: Any?,
        addon: AddonDescriptorV3,
    ): List<AddonMetaItem> {
        val meta = AddonPayloads.parseMetaPayload(body, shapeOf(addon.manifest)) ?: return emptyList()
        return listOf(AddonMetaItem(meta = meta, addonId = addon.id, addedAt = addon.addedAt))
    }

    private fun readStreams(
        body: Any?,
        addon: AddonDescriptorV3,
    ): List<BookStream> = AddonPayloads.parseStreamPayload(body).streams.map { it.copy(addonId = addon.id, addedAt = addon.addedAt) }

    private suspend fun <T> collect(spec: FanoutSpec<T>): FanoutResult<T> {
        val jobs = mutableListOf<Job<T>>()
        val failures = mutableListOf<AddonFailure>()
        val seenUrls = mutableSetOf<String>()
        var skipped = 0

        val selected =
            AddonResourceRouter.selectAddons(
                spec.addons,
                AddonRouteRequest(resource = spec.resource, type = spec.type, id = spec.id),
            )
        for (addon in selected) {
            val extra = spec.extraFor(addon.manifest)
            val request = ResourceRequest(spec.resource, spec.type, spec.id, extra)
            val url =
                try {
                    AddonTemplates.buildRequestUrl(addon, request)
                } catch (_: AddonFetchException) {
                    // An unbuildable request is this addon's defect alone.
                    failures += AddonFailure(addon.id, FailureReason.PAYLOAD)
                    continue
                }

            if (!seenUrls.add(normalizeUrl(url))) continue

            if (!circuit.canRequest(addon.id)) {
                skipped += 1
                continue
            }

            jobs +=
                Job(
                    addon = addon,
                    url = url,
                    cacheKey = cacheKeyOf(addon.id, request, extra),
                    ttlMs = DEFAULT_TTL_MS.getValue(spec.resource),
                    read = spec.read,
                )
        }

        val settled =
            coroutineScope {
                jobs
                    .map { job ->
                        async {
                            try {
                                readAddonResource(job)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                // The isolation guarantee has to survive an adapter throwing.
                                Attempt.failed<T>(FailureReason.PAYLOAD, job.addon.id)
                            }
                        }
                    }.awaitAll()
            }

        val items = mutableListOf<T>()
        for (attempt in settled) {
            val produced = attempt.items
            if (produced == null) {
                val addonId = attempt.addonId
                circuit.recordFailure(addonId)
                failures += AddonFailure(addonId, attempt.reason ?: FailureReason.PAYLOAD)
            } else {
                circuit.recordSuccess(attempt.addonId)
                items += produced
            }
        }
        return FanoutResult(items, failures, skipped)
    }

    private suspend fun <T> readAddonResource(job: Job<T>): Attempt<T> {
        val cached = cacheStore.get(job.cacheKey)
        if (cached != null) {
            @Suppress("UNCHECKED_CAST")
            return Attempt.ok(cached as List<T>, job.addon.id)
        }

        val response =
            try {
                transport.fetch(job.url, timeout)
            } catch (cancellation: CancellationException) {
                if (cancellation is TimeoutCancellationException) {
                    return Attempt.failed(FailureReason.TIMEOUT, job.addon.id)
                }
                throw cancellation
            } catch (error: Exception) {
                return Attempt.failed(classifyTransportError(error), job.addon.id)
            }
        if (response.status !in 200..299) return Attempt.failed(FailureReason.STATUS, job.addon.id)
        if (exceedsBodyCap(response.body)) return Attempt.failed(FailureReason.PAYLOAD, job.addon.id)

        val items =
            try {
                job.read(response.body, job.addon)
            } catch (_: Exception) {
                return Attempt.failed(FailureReason.PAYLOAD, job.addon.id)
            }
        cacheStore.set(job.cacheKey, items, effectiveTtlMs(job.ttlMs, response.cacheControl))
        return Attempt.ok(items, job.addon.id)
    }

    private fun cacheKeyOf(
        addonId: String,
        request: ResourceRequest,
        extra: Map<String, Any?>,
    ): String = "$addonId|${request.resource}|${request.type}|${request.id}|${AddonTemplates.encodeExtra(extra)}"

    private fun declaredOnly(
        manifest: AddonManifestV3,
        catalogId: String,
        extra: Map<String, Any?>?,
    ): Map<String, Any?> {
        if (extra == null) return emptyMap()
        val allowed = AddonResourceRouter.declaredExtras(manifest, catalogId).toSet()
        return extra.filterKeys { allowed.contains(it) }
    }
}
