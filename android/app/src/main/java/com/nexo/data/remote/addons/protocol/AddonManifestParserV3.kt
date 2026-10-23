package com.nexo.data.remote.addons.protocol

import com.nexo.data.remote.addons.AddonFetchErrorCode
import com.nexo.data.remote.addons.AddonFetchException
import com.nexo.data.remote.addons.ManifestValidator
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Manifest v3 parsing: pure, no I/O.
 *
 * The caller fetches bytes through the platform network layer and hands them
 * here. Size cap, HTTPS rule, content-type check and error codes come from
 * [ManifestValidator] so v2 and v3 cannot drift on transport-level policy.
 */
object AddonManifestParserV3 {
    private const val MAX_TYPES = 16
    private const val MAX_TYPE_CHARS = 64
    private const val MAX_RESOURCES = 16
    private const val MAX_CATALOGS = 16
    private const val MAX_CATALOG_ENTRY_CHARS = 512
    private const val MAX_EXTRA_ARGS = 8
    private const val MAX_ID_PREFIXES = 16
    private const val MAX_TEMPLATES = 16
    private const val MAX_STRING_CHARS = 2048
    private const val RESOURCE_META = "meta"

    /** Any brace-delimited token, so `{publisher}` is caught, not passed through. */
    private val TEMPLATE_TOKEN = Regex("\\{[^{}]*\\}")

    private val UNDECODABLE = Any()

    /**
     * Cheap structural probe used to choose the v3 parser. It selects, it never
     * validates: `types` is an array and every `resources` entry is a string or
     * an object carrying a string `name`.
     */
    fun isManifestV3(value: Any?): Boolean {
        if (value !is JSONObject) return false
        if (value.opt("types") !is JSONArray) return false
        val resources = value.opt("resources")
        if (resources !is JSONArray) return false
        return (0 until resources.length()).all { index ->
            val entry = resources.opt(index)
            entry is String || (entry is JSONObject && entry.opt("name") is String)
        }
    }

    /**
     * Validate manifest bytes + upstream content type.
     * Order: size cap (pre-parse) → content type → JSON parse → shape.
     * Unknown top-level fields are ignored.
     */
    fun parseManifestV3(
        bytes: ByteArray,
        contentType: String?,
    ): AddonManifestV3 {
        if (bytes.size > ManifestValidator.MAX_MANIFEST_BYTES) {
            throw AddonFetchException(
                AddonFetchErrorCode.TOO_LARGE,
                "manifest exceeds ${ManifestValidator.MAX_MANIFEST_BYTES} bytes",
            )
        }
        if (!ManifestValidator.isJsonContentType(contentType)) {
            throw AddonFetchException(
                AddonFetchErrorCode.BAD_CONTENT_TYPE,
                "content type is not JSON: $contentType",
            )
        }
        val text = decodeStrict(bytes) ?: invalid("manifest is not valid JSON")
        val parsed =
            try {
                JSONTokener(text).nextValue()
            } catch (_: Exception) {
                invalid("manifest is not valid JSON")
            }
        return parseManifestObject(parsed)
    }

    /**
     * Dispatches on manifest shape, then lets the owning validator report every
     * transport and shape error: v3 has `types` + `resources`, v2 does not.
     * A body that is not JSON falls through to the v2 validator so the caller
     * sees the canonical `ADDON_FETCH_INVALID_MANIFEST`.
     */
    fun loadManifestV3(
        bytes: ByteArray,
        contentType: String?,
    ): AddonManifestV3 {
        val candidate = decodeJson(bytes)
        if (candidate !== UNDECODABLE && isManifestV3(candidate)) {
            return parseManifestV3(bytes, contentType)
        }
        return AddonLegacyAdapter.adaptLegacyManifest(ManifestValidator.validate(bytes, contentType))
    }

    // --------------------------------------------------------------- primitives

    private fun invalid(detail: String): Nothing = throw AddonFetchException(AddonFetchErrorCode.INVALID_MANIFEST, detail)

    private fun decodeStrict(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun decodeJson(bytes: ByteArray): Any? {
        val text = decodeStrict(bytes) ?: return UNDECODABLE
        return try {
            JSONTokener(text).nextValue()
        } catch (_: Exception) {
            UNDECODABLE
        }
    }

    /**
     * Bounded HTTPS string. The scheme decision stays with the shared install-URL
     * rule; a bad value is reported as a shape error because it is a manifest
     * defect. `java.net.URI` rejects the `{placeholder}` characters a template
     * legitimately carries, so placeholders are neutralized for the check only.
     */
    private fun parseHttpsUrl(
        value: Any?,
        label: String,
        maxChars: Int = MAX_STRING_CHARS,
    ): String {
        if (value !is String || value.isEmpty() || value.length > maxChars) {
            invalid("$label must be a non-empty https string")
        }
        val schemeProbe = TEMPLATE_TOKEN.replace(value, "x")
        try {
            ManifestValidator.assertHttpsInstallUrl(schemeProbe)
        } catch (_: AddonFetchException) {
            invalid("$label must be https: $value")
        }
        return value
    }

    private fun parseBoundedString(
        value: Any?,
        label: String,
        maxChars: Int = MAX_STRING_CHARS,
    ): String {
        if (value !is String || value.isEmpty() || value.length > maxChars) {
            invalid("$label must be a non-empty string of at most $maxChars chars")
        }
        return value
    }

    private fun assertSupportedType(entry: String) {
        if (AddonProtocolConstants.RESERVED_TYPES.contains(entry)) {
            throw AddonFetchException(
                AddonFetchErrorCode.UNSUPPORTED_TYPE,
                "type \"$entry\" is reserved and not implemented",
            )
        }
    }

    // ----------------------------------------------------------------- manifest

    private fun parseTypes(
        value: Any?,
        label: String,
    ): List<String> {
        if (value !is JSONArray || value.length() == 0) invalid("$label must be a non-empty array")
        if (value.length() > MAX_TYPES) invalid("too many $label entries")
        return (0 until value.length()).map { index ->
            val parsed = parseBoundedString(value.opt(index), "$label entry", MAX_TYPE_CHARS)
            assertSupportedType(parsed)
            parsed
        }
    }

    private fun parseIdPrefixes(
        value: Any?,
        label: String,
    ): List<String> {
        if (value !is JSONArray || value.length() > MAX_ID_PREFIXES) {
            invalid("$label must be an array of at most $MAX_ID_PREFIXES entries")
        }
        return (0 until value.length()).map { index ->
            parseBoundedString(value.opt(index), "$label entry", MAX_TYPE_CHARS)
        }
    }

    private fun parseResource(value: Any?): AddonResourceV3 {
        if (value is String) {
            return AddonResourceV3(parseBoundedString(value, "resource entry", MAX_TYPE_CHARS))
        }
        if (value !is JSONObject) invalid("resource entry must be a string or an object")
        val name = parseBoundedString(value.opt("name"), "resource name")
        val types = if (value.has("types")) parseTypes(value.opt("types"), "resource types") else null
        val idPrefixes =
            if (value.has("idPrefixes")) {
                parseIdPrefixes(value.opt("idPrefixes"), "resource idPrefixes")
            } else {
                null
            }
        return AddonResourceV3(name = name, types = types, idPrefixes = idPrefixes)
    }

    private fun parseResources(value: Any?): List<AddonResourceV3> {
        if (value !is JSONArray || value.length() == 0) invalid("resources must be a non-empty array")
        if (value.length() > MAX_RESOURCES) invalid("too many resources")
        return (0 until value.length()).map { index -> parseResource(value.opt(index)) }
    }

    private fun parseExtraArgs(value: Any?): List<AddonExtraArg> {
        if (value == null) return emptyList()
        if (value !is JSONArray || value.length() > MAX_EXTRA_ARGS) {
            invalid("catalog extra must be an array of at most $MAX_EXTRA_ARGS entries")
        }
        val args = mutableListOf<AddonExtraArg>()
        for (index in 0 until value.length()) {
            val entry = value.opt(index)
            if (entry !is JSONObject) invalid("catalog extra entry must be an object")
            val name = parseBoundedString(entry.opt("name"), "catalog extra name", MAX_TYPE_CHARS)
            // Unknown extra names are dropped: `catalog[].extra` is the allow-list.
            if (!AddonProtocolConstants.EXTRA_NAMES.contains(name)) continue
            if (entry.has("isRequired") && entry.opt("isRequired") !is Boolean) {
                invalid("catalog extra \"$name\" has a non-boolean isRequired")
            }
            val isRequired = if (entry.has("isRequired")) entry.getBoolean("isRequired") else null
            args += AddonExtraArg(name = name, isRequired = isRequired)
        }
        return args
    }

    private fun parseCatalogEntry(value: Any?): AddonCatalogEntryV3? {
        if (value !is JSONObject) invalid("catalog entry must be an object")
        val type = parseBoundedString(value.opt("type"), "catalog type", MAX_CATALOG_ENTRY_CHARS)
        // Other declared types install successfully and contribute nothing.
        if (type != AddonProtocolConstants.TYPE_BOOK) return null

        val extra = parseExtraArgs(if (value.has("extra")) value.opt("extra") else null)
        return AddonCatalogEntryV3(
            type = type,
            id = parseBoundedString(value.opt("id"), "catalog id", MAX_CATALOG_ENTRY_CHARS),
            name = parseBoundedString(value.opt("name"), "catalog name", MAX_CATALOG_ENTRY_CHARS),
            extra = extra.ifEmpty { null },
        )
    }

    /** Catalogs MAY be empty: a stream-only addon has no catalog. */
    private fun parseCatalogs(value: Any?): List<AddonCatalogEntryV3> {
        if (value == null) return emptyList()
        if (value !is JSONArray) invalid("catalogs must be an array")
        if (value.length() > MAX_CATALOGS) invalid("too many catalogs")
        val catalogs = mutableListOf<AddonCatalogEntryV3>()
        for (index in 0 until value.length()) {
            parseCatalogEntry(value.opt(index))?.let { catalogs += it }
        }
        return catalogs
    }

    private fun parseTemplates(value: Any?): Map<String, String> {
        if (value !is JSONObject) invalid("nexo.templates must be an object")
        val keys = value.keys().asSequence().toList()
        if (keys.size > MAX_TEMPLATES) invalid("too many nexo.templates entries")
        val templates = LinkedHashMap<String, String>()
        for (key in keys) {
            parseBoundedString(key, "nexo.templates key", MAX_TYPE_CHARS)
            templates[key] = parseHttpsUrl(value.opt(key), "nexo.templates.$key")
        }
        return templates
    }

    /**
     * `nexo` is preserved verbatim but bounded, and carries only the four keys
     * NEXO interprets. Unknown keys inside it are dropped, never surfaced.
     */
    private fun parseNexo(value: Any?): AddonNexo? {
        if (value == null) return null
        if (value !is JSONObject) invalid("nexo must be an object")
        if (value.toString().length > AddonProtocolConstants.MAX_NEXO_CHARS) {
            invalid("nexo exceeds ${AddonProtocolConstants.MAX_NEXO_CHARS} chars")
        }
        val homepage = if (value.has("homepage")) parseHttpsUrl(value.opt("homepage"), "nexo.homepage") else null
        val license = if (value.has("license")) parseBoundedString(value.opt("license"), "nexo.license") else null
        val templates =
            if (value.has("templates")) {
                parseTemplates(value.opt("templates"))
            } else {
                emptyMap()
            }
        val shape =
            if (value.has("shape")) {
                val raw = parseBoundedString(value.opt("shape"), "nexo.shape", MAX_TYPE_CHARS)
                AddonShape.fromWire(raw) ?: invalid("nexo.shape \"$raw\" is not a known shape")
            } else {
                null
            }
        return AddonNexo(homepage = homepage, license = license, templates = templates, shape = shape)
    }

    private fun parseBehaviorHints(value: Any?): AddonBehaviorHints? {
        if (value == null) return null
        if (value !is JSONObject) invalid("behaviorHints must be an object")
        if (!value.has("adult")) return AddonBehaviorHints()
        val adult = value.opt("adult")
        if (adult !is Boolean) invalid("behaviorHints.adult must be a boolean")
        return AddonBehaviorHints(adult = adult)
    }

    /**
     * `catalogs` and `meta` are one unit: an addon that lists books must be able
     * to describe them, or it advertises a detail view it cannot answer. An empty
     * `catalogs` list stays valid, so a stream-only addon needs no `meta`.
     */
    private fun assertCatalogMetaPairing(
        catalogs: List<AddonCatalogEntryV3>,
        resources: List<AddonResourceV3>,
    ) {
        if (catalogs.isEmpty()) return
        if (resources.any { it.name == RESOURCE_META }) return
        invalid(
            "catalogs require a \"$RESOURCE_META\" resource: none is declared, " +
                "so the listed books have no detail view",
        )
    }

    private fun parseManifestObject(value: Any?): AddonManifestV3 {
        if (value !is JSONObject) invalid("manifest must be a JSON object")
        val catalogs = parseCatalogs(if (value.has("catalogs")) value.opt("catalogs") else null)
        val resources = parseResources(value.opt("resources"))
        assertCatalogMetaPairing(catalogs, resources)
        return AddonManifestV3(
            id = parseBoundedString(value.opt("id"), "id"),
            version = parseBoundedString(value.opt("version"), "version"),
            name = parseBoundedString(value.opt("name"), "name"),
            types = parseTypes(value.opt("types"), "types"),
            catalogs = catalogs,
            resources = resources,
            description = if (value.has("description")) parseBoundedString(value.opt("description"), "description") else null,
            idPrefixes = if (value.has("idPrefixes")) parseIdPrefixes(value.opt("idPrefixes"), "idPrefixes") else null,
            logo = if (value.has("logo")) parseHttpsUrl(value.opt("logo"), "logo") else null,
            background = if (value.has("background")) parseHttpsUrl(value.opt("background"), "background") else null,
            behaviorHints = parseBehaviorHints(if (value.has("behaviorHints")) value.opt("behaviorHints") else null),
            nexo = parseNexo(if (value.has("nexo")) value.opt("nexo") else null),
        )
    }
}
