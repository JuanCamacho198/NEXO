package com.nexo.data.remote.addons

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure manifest validation for the addon registry (Android mirror of desktop
 * `validateManifest.ts`). No I/O: transport fetches bytes, this validates.
 * Shared additive error codes; the Kotlin enum name IS the wire code.
 */
enum class AddonFetchErrorCode {
    HTTPS_REQUIRED,
    TOO_LARGE,
    BAD_CONTENT_TYPE,
    INVALID_MANIFEST,
    NETWORK,
    ;

    /** Stable additive wire code (ADDON_FETCH_*), shared with desktop TS/Rust. */
    val wireCode: String
        get() = "ADDON_FETCH_$name"
}

class AddonFetchException(
    val code: AddonFetchErrorCode,
    detail: String? = null,
) : Exception("${code.name}: ${detail ?: code.name}")

data class AddonCatalogEntry(
    val type: String,
    val id: String,
    val name: String,
)

data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val catalogs: List<AddonCatalogEntry>,
    val resources: List<String>,
    /** Optional catalog endpoint template: `{query}`, `{page}` placeholders. */
    val searchUrl: String? = null,
    /** Optional detail endpoint template: `{bookId}` placeholder. */
    val detailsUrl: String? = null,
    /**
     * Optional v2 resolve endpoint template: `{isbn}`, `{title}`,
     * `{author}`, `{openLibraryId}`, `{googleBooksId}` placeholders.
     * Absent ⇒ the addon has no resolve capability (resolve stays empty).
     */
    val resolveUrl: String? = null,
    /**
     * Optional v2 capability list (e.g. `"resolve"`). Absent ⇒ empty;
     * disclosed to the user before consent is recorded (U5 UI).
     */
    val capabilities: List<String> = emptyList(),
)

/**
 * U4 per-item access type for v2 resolve payloads. Wire values are
 * lowercase; unknown raw values parse to null so callers fail closed
 * (never treated as free).
 */
enum class AddonAccessType {
    FREE,
    BUY,
    SUBSCRIBE,
}

/** Rules and codes mirror desktop validateManifest.ts byte-for-byte. */
object ManifestValidator {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_CATALOGS = 16
    private const val MAX_CATALOG_ENTRY_CHARS = 512
    private const val MAX_RESOURCES = 16
    private const val MAX_RESOURCE_CHARS = 64
    private const val MAX_ENDPOINT_CHARS = 2048
    private const val MAX_CAPABILITIES = 16
    private const val MAX_CAPABILITY_CHARS = 64
    private const val ACCESS_TYPE_FREE = "free"
    private const val ACCESS_TYPE_BUY = "buy"
    private const val ACCESS_TYPE_SUBSCRIBE = "subscribe"

    /** License tokens treated as cleared for the in-app download path (closed set). */
    private val LICENSE_CLEARED_TOKENS = setOf("public-domain", "public domain", "cc0", "cc0-1.0", "pd")

    internal fun isJsonContentType(contentType: String?): Boolean {
        if (contentType == null) return false
        val base = contentType.substringBefore(';').trim().lowercase()
        return base == "application/json" || base.endsWith("+json")
    }

    /** HTTPS-only install URLs: reject before any network I/O. */
    fun assertHttpsInstallUrl(url: String): Boolean {
        val scheme =
            runCatching {
                java.net
                    .URI(url)
                    .scheme
                    ?.lowercase()
            }.getOrNull()
        if (scheme != "https") {
            throw AddonFetchException(AddonFetchErrorCode.HTTPS_REQUIRED, "install URL must be https: $url")
        }
        return true
    }

    private fun isJsonContentTypeRemoved(contentType: String?): Boolean = isJsonContentType(contentType)

    private fun invalid(detail: String): Nothing = throw AddonFetchException(AddonFetchErrorCode.INVALID_MANIFEST, detail)

    /** Exact-prefix parse of a manifest-declared endpoint: https-only, bounded.
     * Placeholder braces (`{query}`) are legal in templates, so this is a
     * scheme-prefix check rather than a strict URI parse (desktop's WHATWG
     * `new URL` accepts them too). */
    private fun parseEndpoint(value: Any?): String? {
        if (value == null) return null
        if (value !is String || value.isEmpty() || value.length > MAX_ENDPOINT_CHARS) {
            invalid("endpoint must be a non-empty https string")
        }
        if (!value.lowercase().startsWith("https://")) invalid("endpoint must be https: $value")
        return value
    }

    private fun nonEmptyString(value: Any?): Boolean = value is String && value.isNotEmpty()

    /**
     * U4: parse a v2 per-item `accessType` wire value. Unknown or missing
     * values return null so resolve callers fail closed (never free).
     */
    fun parseAccessType(raw: String?): AddonAccessType? =
        when (raw?.trim()?.lowercase()) {
            ACCESS_TYPE_FREE -> AddonAccessType.FREE
            ACCESS_TYPE_BUY -> AddonAccessType.BUY
            ACCESS_TYPE_SUBSCRIBE -> AddonAccessType.SUBSCRIBE
            else -> null
        }

    /** U4: true only for license tokens in the closed cleared set (case-insensitive). */
    fun isLicenseCleared(license: String?): Boolean = license?.trim()?.lowercase() in LICENSE_CLEARED_TOKENS

    /** U4: optional endpoint — absent/null ⇒ skipped (no validation); present ⇒ https rules. */
    private fun parseOptionalEndpoint(value: Any?): String? {
        if (value == null || value == JSONObject.NULL) return null
        return parseEndpoint(value)
    }

    /** U4: optional capabilities — absent/null ⇒ empty (skipped); present ⇒ string-array rules. */
    private fun parseCapabilities(value: Any?): List<String> {
        if (value == null || value == JSONObject.NULL) return emptyList()
        if (value !is JSONArray) invalid("capabilities must be an array of strings")
        if (value.length() > MAX_CAPABILITIES) invalid("too many capabilities")
        return (0 until value.length()).map { i ->
            val entry = value.opt(i)
            if (!nonEmptyString(entry) || (entry as String).length > MAX_CAPABILITY_CHARS) {
                invalid("invalid capability entry")
            }
            entry
        }
    }

    private fun parseCatalogEntry(entry: Any?): AddonCatalogEntry {
        if (entry !is JSONObject) invalid("catalog entry must be an object")
        for (key in listOf("type", "id", "name")) {
            val value = entry.opt(key)
            if (!nonEmptyString(value) || (value as String).length > MAX_CATALOG_ENTRY_CHARS) {
                invalid("catalog entry missing $key")
            }
        }
        return AddonCatalogEntry(
            type = entry.getString("type"),
            id = entry.getString("id"),
            name = entry.getString("name"),
        )
        // unknown entry fields ignored
    }

    private fun parseResources(value: Any?): List<String> {
        if (value !is JSONArray || value.length() == 0) invalid("resources must be a non-empty array")
        if (value.length() > MAX_RESOURCES) invalid("too many resources")
        return (0 until value.length()).map { i ->
            val entry = value.opt(i)
            if (!nonEmptyString(entry) || (entry as String).length > MAX_RESOURCE_CHARS) {
                invalid("invalid resource entry")
            }
            entry
        }
    }

    private fun parseManifestObject(value: Any?): AddonManifest {
        if (value !is JSONObject) invalid("manifest must be a JSON object")
        for (key in listOf("id", "name", "version")) {
            if (!nonEmptyString(value.opt(key))) invalid("missing required field $key")
        }
        val catalogs = value.opt("catalogs")
        if (catalogs !is JSONArray || catalogs.length() == 0) invalid("catalogs must be a non-empty array")
        if (catalogs.length() > MAX_CATALOGS) invalid("too many catalogs")
        return AddonManifest(
            id = value.getString("id"),
            name = value.getString("name"),
            version = value.getString("version"),
            catalogs = (0 until catalogs.length()).map { parseCatalogEntry(catalogs.opt(it)) },
            resources = parseResources(value.opt("resources")),
            searchUrl = parseEndpoint(value.opt("searchUrl")),
            detailsUrl = parseEndpoint(value.opt("detailsUrl")),
            resolveUrl = parseOptionalEndpoint(value.opt("resolveUrl")),
            capabilities = parseCapabilities(value.opt("capabilities")),
        )
    }

    /**
     * Validate manifest bytes + upstream content type.
     * Order: size cap (pre-parse) → content type → JSON parse → shape.
     */
    fun validate(
        bytes: ByteArray,
        contentType: String?,
    ): AddonManifest {
        if (bytes.size > MAX_MANIFEST_BYTES) {
            throw AddonFetchException(AddonFetchErrorCode.TOO_LARGE, "manifest exceeds $MAX_MANIFEST_BYTES bytes")
        }
        if (!isJsonContentType(contentType)) {
            throw AddonFetchException(AddonFetchErrorCode.BAD_CONTENT_TYPE, "content type is not JSON: $contentType")
        }
        val parsed =
            try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                invalid("manifest is not valid JSON")
            }
        return parseManifestObject(parsed)
    }
}
