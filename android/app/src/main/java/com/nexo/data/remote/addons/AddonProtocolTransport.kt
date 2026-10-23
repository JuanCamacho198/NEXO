package com.nexo.data.remote.addons

import com.nexo.data.remote.addons.protocol.AddonResourceTransport
import com.nexo.data.remote.addons.protocol.ResourceResponse
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.json.JSONTokener
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * "Max 3 concurrent requests per host", owned where the sockets live.
 *
 * The protocol package builds the request and classifies the failure; it cannot
 * see a socket pool, so the budget belongs to each platform transport.
 */
const val MAX_CONCURRENT_REQUESTS_PER_HOST: Int = 3

/**
 * Protocol transport over the platform fetch.
 *
 * Non-2xx answers keep their status and carry no body, so the client reports
 * `STATUS` instead of a parse error. Every 2xx body is parsed here: a decode or
 * JSON failure surfaces as [org.json.JSONException], which the client classifies
 * as `PAYLOAD` rather than a connectivity problem. A `timeoutMs` of `0` means
 * "no deadline"; anything positive bounds the whole request.
 */
class AddonProtocolTransport(
    private val transport: AddonHttpTransport,
    private val maxConcurrentPerHost: Int = MAX_CONCURRENT_REQUESTS_PER_HOST,
) : AddonResourceTransport {
    private val capacity: Int = maxOf(1, maxConcurrentPerHost)
    private val hostSlots = ConcurrentHashMap<String, Semaphore>()

    override suspend fun fetch(
        url: String,
        timeoutMs: Long,
    ): ResourceResponse {
        val host = hostOf(url) ?: return perform(url, timeoutMs)
        return slotsFor(host).withPermit { perform(url, timeoutMs) }
    }

    private suspend fun perform(
        url: String,
        timeoutMs: Long,
    ): ResourceResponse {
        val resource =
            if (timeoutMs > 0) {
                withTimeout(timeoutMs) { transport.fetch(url) }
            } else {
                transport.fetch(url)
            }
        if (resource.status !in 200..299) {
            return ResourceResponse(resource.status, null, resource.cacheControl)
        }
        return ResourceResponse(resource.status, parseJson(resource.body), resource.cacheControl)
    }

    private fun slotsFor(host: String): Semaphore = hostSlots.computeIfAbsent(host) { Semaphore(capacity) }
}

/** Lowercase host, or `null` when the URL cannot name one (then no budget applies). */
private fun hostOf(url: String): String? =
    try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

/**
 * Bytes → JSON. Strict UTF-8 so a mangled body is a payload defect; the
 * tokener keeps the exact value the addon sent instead of re-shaping it.
 *
 * A payload the router can never read — a bare string, a number, a truncated
 * document — is reported as `JSONException`, which the client classifies as
 * `PAYLOAD`. The `JSONTokener` alone would hand back a `String` for `not json`.
 */
private fun parseJson(body: ByteArray): Any? {
    val decoder =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    val text =
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(body)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw org.json.JSONException("addon payload is not valid UTF-8")
        }
    val value = JSONTokener(text).nextValue()
    if (value !is org.json.JSONObject && value !is org.json.JSONArray) {
        throw org.json.JSONException("addon payload is not a JSON object or array")
    }
    return value
}
