package com.nexo.data.remote.addons.protocol

import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import java.net.URLDecoder

/**
 * Request-URL construction. Pure, no I/O.
 *
 * Tier B (`nexo.templates[resource]`) wins over the routed form; templates are a
 * client-side courtesy to addons that cannot host Stremio routes. `installUrl`
 * is only ever a string: provenance lives in the install record, so this module
 * cannot leak credentials or ambient state into a URL.
 */
data class ResourceRequest(
    val resource: String,
    val type: String,
    val id: String,
    val extra: Map<String, Any?>? = null,
)

object AddonTemplates {
    private val MANIFEST_SUFFIX = Regex("/manifest\\.json$")
    private val TRAILING_SLASHES = Regex("/+$")

    /** Any brace-delimited token, so `{publisher}` is caught, not passed through. */
    private val TEMPLATE_TOKEN = Regex("\\{([^{}]*)\\}")

    private const val HEX = "0123456789ABCDEF"

    /**
     * `encodeURIComponent` unescaped set. Reimplemented because
     * `URLEncoder.encode` emits `+` for a space and leaves different characters
     * alone; the protocol fixes `%20` and the exact escape set.
     */
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    private fun invalid(detail: String): Nothing = throw AddonFetchException(AddonFetchErrorCode.INVALID_MANIFEST, detail)

    /** `https://a.test/addons/x/manifest.json/` → `https://a.test/addons/x`. */
    fun resolveAddonBase(installUrl: String): String =
        installUrl
            .trim()
            .replace(TRAILING_SLASHES, "")
            .replace(MANIFEST_SUFFIX, "")
            .replace(TRAILING_SLASHES, "")

    /**
     * `encodeURIComponent` with `%3A` restored to `:`, so `isbn:978…` stays
     * readable in a path segment and in an extra value.
     */
    fun encodeSegment(value: String): String {
        val encoded = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (code < 0x80 && UNRESERVED.indexOf(char) >= 0) {
                encoded.append(char)
            } else {
                encoded.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
        return encoded.toString().replace("%3A", ":")
    }

    /**
     * Serializes extra arguments in insertion order. Nullish and empty-string
     * values are skipped: an absent filter must not become `genre=`.
     */
    fun encodeExtra(extra: Map<String, Any?>?): String {
        if (extra == null) return ""
        val parts = mutableListOf<String>()
        for ((key, value) in extra) {
            if (value == null || value == "") continue
            parts += "${encodeSegment(key)}=${encodeSegment(stringify(value))}"
        }
        return parts.joinToString("&")
    }

    /** Inverse of [encodeExtra] for a client that receives a routed path. */
    fun decodeExtra(raw: String): Map<String, String> {
        val decoded = LinkedHashMap<String, String>()
        for (segment in raw.split("&")) {
            val separator = segment.indexOf('=')
            if (separator <= 0) continue
            val key = decodePart(segment.substring(0, separator)) ?: continue
            val value = decodePart(segment.substring(separator + 1)) ?: continue
            decoded[key] = value
        }
        return decoded
    }

    /**
     * A `/` in a type or id would forge an extra path segment, so it is a
     * manifest defect rather than something to percent-encode into an
     * unreadable URL.
     */
    private fun assertRoutableSegment(
        value: String,
        label: String,
    ) {
        if (value.isEmpty()) invalid("request $label must be non-empty")
        if (value.contains('/')) invalid("request $label must not contain \"/\": $value")
    }

    /** `{base}/{resource}/{type}/{id}[/{extra}].json` */
    fun buildRoutedUrl(
        installUrl: String,
        req: ResourceRequest,
    ): String {
        val base = resolveAddonBase(installUrl)
        if (base.isEmpty()) invalid("install URL has no base: $installUrl")
        assertRoutableSegment(req.type, "type")
        assertRoutableSegment(req.id, "id")

        val extra = encodeExtra(req.extra)
        val tail = if (extra.isNotEmpty()) "/$extra" else ""
        return "$base/${req.resource}/${encodeSegment(req.type)}/${encodeSegment(req.id)}$tail.json"
    }

    /** A template for that resource wins, otherwise the routed form. */
    fun buildRequestUrl(
        addon: AddonDescriptorV3,
        req: ResourceRequest,
    ): String {
        val template =
            addon.manifest.nexo
                ?.templates
                ?.get(req.resource)
        if (template != null) return fillTemplate(template, req)
        return buildRoutedUrl(addon.installUrl, req)
    }

    fun fillTemplate(
        template: String,
        req: ResourceRequest,
    ): String = TEMPLATE_TOKEN.replace(template) { match -> encodeSegment(placeholderValue(match.groupValues[1], req)) }

    /**
     * `{query}` and `{page}` are legacy aliases of `{search}` and `{skip}`, and
     * `{bookId}` of `{id}`. A request that omits an argument yields an empty
     * substitution, never a literal token sent upstream.
     */
    private fun placeholderValue(
        token: String,
        req: ResourceRequest,
    ): String {
        val extra = req.extra ?: emptyMap()
        return when (token) {
            "search", "query" -> stringify(extra["search"] ?: extra["query"])
            "skip", "page" -> stringify(extra["skip"] ?: extra["page"])
            "genre" -> stringify(extra["genre"])
            "lang" -> stringify(extra["lang"])
            "id", "bookId" -> req.id
            else -> invalid("template placeholder \"{$token}\" is not allowed")
        }
    }

    /** `String(value)` on the JS side: an integral double prints without `.0`. */
    private fun stringify(value: Any?): String =
        when (value) {
            null -> ""
            is Double -> if (value.isFinite() && value == Math.floor(value)) value.toLong().toString() else value.toString()
            is Float -> if (value.isFinite() && value == Math.floor(value.toDouble())) value.toLong().toString() else value.toString()
            else -> value.toString()
        }

    private fun decodePart(raw: String): String? =
        try {
            // `decodeURIComponent` leaves `+` alone, unlike `URLDecoder`.
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            null
        }
}
