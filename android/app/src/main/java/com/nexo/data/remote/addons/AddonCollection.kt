package com.nexo.data.remote.addons

import com.nexo.data.remote.addons.protocol.AddonManifestParserV3
import com.nexo.data.remote.addons.protocol.AddonManifestV3
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Addon collections: one link installs a whole set. Pure, no I/O.
 *
 * The wire shape is Stremio's (what `stremio-addon-client` ships):
 * `{ "addons": [ { "manifest": {…}, "transportUrl": "https://…",
 * "transportName": "http", "flags": {} } ] }`. A bare array of those entries is
 * accepted too. Mirrors `packages/addon-protocol/src/collection.ts`.
 *
 * A collection is untrusted input written by a stranger, so an import is held to
 * stricter terms than a single install:
 *
 * - `transportUrl` must be HTTPS. An `http:` entry is skipped, never upgraded or
 *   downgraded, because the collection would otherwise choose the scheme.
 * - `flags` is DROPPED, never read. Stremio collections carry a `flags` object
 *   describing transport quirks; here it would be a field a stranger controls.
 *   Do NOT "fix" this by honouring a flag that grants an addon trust, marks it
 *   official, or records stream consent: a collection cannot vouch for its own
 *   entries, and it cannot consent on the reader's behalf. `stream` consent is
 *   per addon, asked individually at install time, and lives in the device's
 *   [AddonConsentStore].
 * - One bad entry never fails the set: a malformed manifest, a non-object entry
 *   or an `http:` URL is counted in [AddonCollectionParseResult.skipped] and the
 *   rest still import.
 * - At most [AddonCollectionParser.MAX_ENTRIES] entries install; the overflow is
 *   reported as skipped rather than silently truncated.
 */
data class AddonCollectionEntry(
    /** The validated v3 view the confirmation lists. */
    val manifest: AddonManifestV3,
    /** The bytes the collection embedded, so install persists exactly those. */
    val rawJson: String,
    val transportUrl: String,
) {
    /**
     * `stream` is the one resource that needs per-addon consent. The collection
     * can only mark it; the reader grants it.
     */
    val requestsStream: Boolean = manifest.resources.any { it.name == RESOURCE_STREAM }

    private companion object {
        const val RESOURCE_STREAM = "stream"
    }
}

data class AddonCollectionParseResult(
    /** Importable entries, in collection order, deduplicated by transportUrl. */
    val entries: List<AddonCollectionEntry>,
    /** Entries refused: non-objects, non-HTTPS urls, invalid manifests, overflow. */
    val skipped: Int,
)

object AddonCollectionParser {
    /** Hard ceiling on one import. Overflow counts toward [AddonCollectionParseResult.skipped]. */
    const val MAX_ENTRIES = 40

    /** Embedded manifests are re-validated through the install path. */
    private const val EMBEDDED_MANIFEST_CONTENT_TYPE = "application/json"

    /**
     * Parse a fetched collection. Never throws: input that is not a collection
     * yields no entries and no skips, so the caller can report "this link holds
     * no addons" instead of failing the flow.
     */
    fun parse(json: String): AddonCollectionParseResult {
        val addons = readAddonList(json) ?: return AddonCollectionParseResult(emptyList(), 0)
        val entries = mutableListOf<AddonCollectionEntry>()
        val seen = mutableSetOf<String>()
        var skipped = 0

        for (index in 0 until addons.length()) {
            val raw = addons.opt(index)
            if (raw !is JSONObject) {
                skipped++
                continue
            }
            // `raw.optJSONObject("flags")` is deliberately never read: see the
            // class comment. Every field except `manifest` and `transportUrl`
            // is untrusted decoration.
            val transportUrl = raw.opt("transportUrl") as? String
            if (transportUrl == null || !isHttpsUrl(transportUrl)) {
                skipped++
                continue
            }
            // Duplicates collapse to one entry: the same addon listed twice is
            // not an error, and the first occurrence is the one the author
            // wrote first.
            if (seen.contains(transportUrl)) continue
            if (entries.size >= MAX_ENTRIES) {
                skipped++
                continue
            }
            val manifestObject = raw.optJSONObject("manifest")
            if (manifestObject == null) {
                skipped++
                continue
            }
            val manifest = readManifest(manifestObject)
            if (manifest == null) {
                skipped++
                continue
            }
            seen.add(transportUrl)
            entries.add(
                AddonCollectionEntry(
                    manifest = manifest,
                    rawJson = manifestObject.toString(),
                    transportUrl = transportUrl,
                ),
            )
        }
        return AddonCollectionParseResult(entries, skipped)
    }

    /** The entry list, or null when the input is not a collection at all. */
    private fun readAddonList(json: String): JSONArray? {
        if (json.isBlank()) return null
        val decoded =
            runCatching { JSONTokener(json).nextValue() }.getOrNull() ?: return null
        return when (decoded) {
            is JSONArray -> decoded
            is JSONObject -> decoded.optJSONArray("addons")
            else -> null
        }
    }

    /** HTTPS enforcement is the shared install-URL rule, so v2 and v3 cannot drift. */
    private fun isHttpsUrl(value: String): Boolean = runCatching { ManifestValidator.assertHttpsInstallUrl(value) }.isSuccess

    /**
     * Validate the embedded manifest exactly as an install would: size cap,
     * content type, v3 shape, or the lossless v2 adapter. A failure here is the
     * entry's problem alone, so it is reported as null, never thrown.
     */
    private fun readManifest(manifest: JSONObject): AddonManifestV3? =
        runCatching {
            AddonManifestParserV3.loadManifestV3(
                manifest.toString().toByteArray(Charsets.UTF_8),
                EMBEDDED_MANIFEST_CONTENT_TYPE,
            )
        }.getOrNull()
}
