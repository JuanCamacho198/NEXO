package com.nexo.data.sync

/**
 * LocatorCodec — Android parity for desktop `LocatorCodec.ts`.
 *
 * Canonical Readium Locator JSON codec — cross-device continuity.
 * Single canonical locator string stored in `locator_json` and consumed
 * by both NEXO engines (desktop + android). Shape (Readium):
 * {"href":"chapter/001.xhtml","type":"application/xhtml+xml",
 *  "locations":{"progression":0.37,"fragment":"epubcfi(...)"}}
 *
 * Responsibilities:
 * - Resolve a precise epubjs CFI to an href using the reading order (spine).
 * - Compute within-chapter progression from char offset.
 * - Round-trip a locator back to its precise CFI (cfiFromLocator).
 * - Serialise/deserialise the canonical JSON (locatorToJson / locatorFromJson).
 *
 * CFI spine-prefix format is the same as cfiBridge:
 * `epubcfi(/6/{spineIndex}!)...` — spineIndex is 1-based.
 * readingOrder = spineHrefs (authoritative OPF spine order, linear=no filtered)
 *
 * Parity with desktop/src/lib/shared/sync/LocatorCodec.ts — keep in lockstep.
 * Golden vectors: desktop/src/test/unit/sync/LocatorCodec.golden.test.ts
 */

data class LocatorChapterMetric(
    val chapterChars: Int,
    val charOffset: Int,
)

data class LocatorLocations(
    var progression: Double? = null,
    var fragment: String? = null,
)

data class CanonicalLocator(
    var href: String,
    var type: String,
    var locations: LocatorLocations,
)

object LocatorCodec {
    private const val FALLBACK_TYPE = "application/xhtml+xml"
    private val SPINE_INDEX_RE = Regex("""^epubcfi\(/6/(\d+)""")

    /**
     * Normalize an href to the form an EPUB/Readium href must take (Windows backslash fix), and
     * collapse a doubled slash left in already-persisted data by the corruption that shipped
     * before T2. A separator is always a single `/` in an EPUB href (OCF container filenames are
     * ZIP entries; an empty path segment is not a legal filename), and an href is always a
     * publication-relative path — never an absolute URL with an authority. So `//` has no legal
     * occurrence in the value this field may hold, and collapsing it is lossless.
     */
    fun normalizeHref(href: String): String = collapseRepeatedSlashes(href.replace("\\", "/"))

    /**
     * Collapse runs of `/` in a resource href. A path segment cannot be empty, so a repeated
     * slash is always an artefact: either the Windows artefact above or the `\/`-escaping
     * corruption that turned `OEBPS\/x.xhtml` into `OEBPS//x.xhtml` on write. Idempotent, so it
     * is safe to apply on every read. Applied per FIELD — never as a blanket replace over the
     * whole JSON — and `locations.fragment` (an epubcfi) is left alone entirely.
     */
    private fun collapseRepeatedSlashes(href: String): String = href.replace(REPEATED_SLASH_RE, "/")

    /** Matches the `"href":"<value>"` pair, so the regex fallback touches only the href value. */
    private val HREF_PAIR_RE = Regex(""""href"\s*:\s*"([^"]*)"""")

    /** A run of two or more `/` — never legal inside an href (see [collapseRepeatedSlashes]). */
    private val REPEATED_SLASH_RE = Regex("/{2,}")

    /**
     * Collapse a doubled slash in a `type` media type. The corruption that shipped before T2 hit
     * BOTH fields, and `type` is not inert: Readium's `Locator.fromJSON` parses it as a
     * `MediaType` and returns null for the WHOLE locator when it is malformed. Measured on device:
     * `{"href":"OEBPS/ch.xhtml","type":"application//xhtml+xml"}` parses to null, while the same
     * JSON with `application/xhtml+xml` parses. Without this the href repair alone changes nothing
     * about resume — the reader still lands on the cover. A media type is `type/subtype` with
     * exactly one structural slash, so a run of two is never legal and collapsing it is lossless.
     */
    private fun normalizeMediaType(type: String): String = type.replace(REPEATED_SLASH_RE, "/")

    /**
     * Normalize a locator JSON string: repair the `href` and `type` fields, and leave the input
     * untouched when there is nothing to repair.
     *
     * Both repairs are idempotent:
     * - a real backslash (Windows path artefact) becomes a forward slash;
     * - a doubled slash — the shape the pre-T2 writer persisted, `OEBPS//x.xhtml` — collapses to
     *   a single slash. That shape is *read-path repaired* rather than migrated: existing rows
     *   already hold it, and a single-statement bulk UPDATE would rewrite rows whose corruption
     *   pattern differs, could not be validated row by row, and could not be undone, whereas
     *   normalising on read is idempotent, testable, and heals only what the reader actually uses.
     *
     * Parity with desktop `normalizeLocatorJson` — parse FIRST, inspect the PARSED href, and never
     * test the RAW text for a backslash: the platform `org.json` serializer escapes `/` as `\/`,
     * so the serialized text of a clean locator always contains one and a blanket replace would
     * turn `OEBPS\/x.xhtml` into `OEBPS//x.xhtml`. Every rewrite is applied to a parsed FIELD —
     * never to the serialized text — so an unrelated field can never be mangled.
     *
     * Falls back to a targeted regex over the `"href"` pair when the JSON does not parse.
     */
    fun normalizeLocatorJson(json: String?): String? {
        if (json.isNullOrEmpty()) return json
        return try {
            val obj = org.json.JSONObject(json)
            val href = if (obj.has("href")) obj.optString("href") else ""
            val type = if (obj.has("type")) obj.optString("type") else ""
            val normalizedHref = normalizeHref(href)
            val normalizedType = normalizeMediaType(type)
            if (normalizedHref == href && normalizedType == type) return json
            if (normalizedHref != href) obj.put("href", normalizedHref)
            if (normalizedType != type) obj.put("type", normalizedType)
            obj.toString()
        } catch (_: Exception) {
            // Unparseable input: rewrite only the `"href"` value, never the serialized text
            // wholesale — a blanket replace would also rewrite the `type` media type.
            val match = HREF_PAIR_RE.find(json) ?: return json
            val normalized = normalizeHref(match.groupValues[1])
            if (normalized == match.groupValues[1]) return json
            json.replace(HREF_PAIR_RE) { m -> "\"href\":\"${normalizeHref(m.groupValues[1])}\"" }
        }
    }

    /**
     * Extract 1-based spine index from a CFI string. Returns null for malformed/cfi missing.
     */
    fun parseSpineIndex(cfi: String?): Int? {
        if (cfi.isNullOrEmpty()) return null
        val m = SPINE_INDEX_RE.find(cfi) ?: return null
        val raw = m.groupValues.getOrNull(1) ?: return null
        val parsed = raw.toIntOrNull() ?: return null
        if (parsed < 1) return null
        return parsed
    }

    /** Compute a clamped [0, 1] within-chapter progression. Returns null if total is non-positive. */
    fun charOffsetToProgression(
        charOffset: Int,
        chapterChars: Int,
    ): Double? {
        if (chapterChars <= 0) return null
        return (charOffset.toDouble() / chapterChars.toDouble()).coerceIn(0.0, 1.0)
    }

    /** Overload for Double to match TS Number path */
    fun charOffsetToProgression(
        charOffset: Double,
        chapterChars: Double,
    ): Double? {
        if (!chapterChars.isFinite() || chapterChars <= 0) return null
        if (!charOffset.isFinite()) return 0.0
        return (charOffset / chapterChars).coerceIn(0.0, 1.0)
    }

    /**
     * Resolve a precise CFI to a canonical locator for the given reading order (spineHrefs).
     * The spine index (from `/6/{N}`) maps to `readingOrder[N-1]`.
     * readingOrder = spineHrefs (authoritative OPF order, linear=no filtered)
     */
    fun locatorFromCfi(
        readingOrder: List<String>,
        cfi: String?,
        chapter: LocatorChapterMetric?,
    ): CanonicalLocator? {
        val spineIndex = parseSpineIndex(cfi) ?: return null
        val rawHref = readingOrder.getOrNull(spineIndex - 1) ?: return null
        val href = normalizeHref(rawHref)
        val locations = LocatorLocations()
        if (chapter != null) {
            val progression = charOffsetToProgression(chapter.charOffset, chapter.chapterChars)
            if (progression != null) locations.progression = progression
        }
        if (!cfi.isNullOrEmpty()) {
            locations.fragment = cfi
        }
        return CanonicalLocator(href = href, type = FALLBACK_TYPE, locations = locations)
    }

    /**
     * Derive a chapter-anchored locator (progression 0.0, no precise CFI) for an
     * href present in the reading order. Used for legacy rows that only carry a
     * chapter reference (no mid-chapter precision).
     */
    fun deriveLocatorForChapter(
        readingOrder: List<String>,
        chapterHref: String?,
    ): CanonicalLocator? {
        if (chapterHref.isNullOrEmpty()) return null
        val normalizedChapterHref = normalizeHref(chapterHref)
        val normalizedOrder = readingOrder.map { normalizeHref(it) }
        if (!normalizedOrder.contains(normalizedChapterHref)) return null
        return CanonicalLocator(
            href = normalizedChapterHref,
            type = FALLBACK_TYPE,
            locations = LocatorLocations(progression = 0.0),
        )
    }

    /** Return the precise CFI stored on the locator (round-trip), or null. */
    fun cfiFromLocator(loc: CanonicalLocator?): String? {
        if (loc?.locations?.fragment != null && loc.locations.fragment!!.isNotEmpty()) {
            return loc.locations.fragment
        }
        return null
    }

    /** Serialise a locator to the canonical Readium JSON string. */
    fun locatorToJson(loc: CanonicalLocator): String {
        val obj = org.json.JSONObject()
        obj.put("href", normalizeHref(loc.href))
        obj.put("type", loc.type)
        val locations = org.json.JSONObject()
        loc.locations.progression?.let { locations.put("progression", it) }
        loc.locations.fragment?.let { locations.put("fragment", it) }
        obj.put("locations", locations)
        return obj.toString()
    }

    /** Deserialise a canonical locator JSON string. Returns null on invalid input. */
    fun locatorFromJson(json: String?): CanonicalLocator? {
        if (json.isNullOrEmpty()) return null
        val normalizedJson = normalizeLocatorJson(json) ?: json
        return try {
            val raw = org.json.JSONObject(normalizedJson)
            val hrefRaw = raw.optString("href", "")
            if (hrefRaw.isEmpty()) return null
            val href = normalizeHref(hrefRaw)
            val locations = LocatorLocations()
            val locObj = if (raw.has("locations") && !raw.isNull("locations")) raw.optJSONObject("locations") else null
            if (locObj != null) {
                if (locObj.has("progression") && !locObj.isNull("progression")) {
                    val prog = locObj.optDouble("progression", Double.NaN)
                    if (!prog.isNaN()) locations.progression = prog
                }
                if (locObj.has("fragment") && !locObj.isNull("fragment")) {
                    val frag = locObj.optString("fragment", "")
                    if (frag.isNotEmpty()) locations.fragment = frag
                }
            }
            val typeRaw = raw.optString("type", "")
            val type = if (typeRaw.isNotEmpty()) typeRaw else FALLBACK_TYPE
            CanonicalLocator(href = href, type = type, locations = locations)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Derived page helper — CFI-first page resolution.
     * Returns 1 for any valid `epubcfi(...)`, null when CFI missing/malformed.
     * Callers use `fromCfi(cfiRange ?: cfiLocation) ?: 1`.
     * `current_page` / `pageNumber` are deprecated as sync sources — display-only.
     */
    fun fromCfi(cfi: String?): Int? {
        val idx = parseSpineIndex(cfi)
        return if (idx != null) 1 else null
    }

    /** Alias for callers that expect `derivePage`. */
    fun derivePage(cfi: String?): Int? = fromCfi(cfi)

    /** @deprecated Use `fromCfi(cfi) ?: 1` — `current_page` is no longer canonical. */
    const val currentPageDeprecated: Boolean = true
}
