package com.nexo.presentation.viewmodel

import com.nexo.data.sync.LocatorCodec
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/** Converts desktop epubjs CFI values and legacy chapter positions to Readium locators. */
object CfiMigrator {
    private val LEGACY_CFI_REGEX = Regex("""^epubcfi\(/6/(\d+)\)$""")
    private val TERMINUS_OFFSET_REGEX = Regex(""":(\d+)$""")

    data class ParsedPreciseCfi(
        val spineIndex: Int,
        val localPath: List<Int>,
        val textOffset: Int,
    )

    data class TextMetric(
        val charOffset: Int,
        val chapterChars: Int,
    )

    /**
     * Converts a legacy CFI string to a Readium Locator.
     * Returns null if the CFI format is invalid or the chapter index is out of range.
     *
     * Builds the Locator via JSON to avoid depending on Readium's internal
     * [Url] and [MediaType] constructor types directly.
     *
     * @param cfiString e.g. "epubcfi(/6/3)" — chapter index is 1-based
     * @param readingOrder the publication's readingOrder (list of Link)
     */
    fun migrateCfiToLocator(
        cfiString: String,
        readingOrder: List<Link>,
    ): Locator? {
        val chapterIndex =
            LEGACY_CFI_REGEX
                .find(cfiString)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.minus(1) // Convert to 0-based
                ?: return null

        val link = readingOrder.getOrNull(chapterIndex) ?: return null

        // Build a minimal Locator JSON matching Readium's serialization format.
        val json =
            JSONObject().apply {
                put("href", link.href.toString())
                put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                put(
                    "locations",
                    JSONObject().apply {
                        put("progression", 0.0)
                    },
                )
            }
        return Locator.fromJSON(json)
    }

    /**
     * Translates a precise epubjs range CFI into a canonical Readium Locator.
     *
     * The caller supplies chapter text geometry because Readium does not expose
     * the EPUB DOM's text-node offsets. A missing metric is deliberately a hard
     * failure: precise CFIs must never silently become chapter-start locators.
     * The raw CFI is retained as `locations.fragment` for native re-anchoring.
     */
    fun preciseCfiToLocator(
        cfiString: String,
        readingOrder: List<Link>,
        charLenFn: (Link, ParsedPreciseCfi) -> TextMetric?,
    ): Locator? {
        val parsed = parsePreciseCfi(cfiString) ?: return null
        val link = readingOrder.getOrNull(parsed.spineIndex - 1) ?: return null
        val metric = charLenFn(link, parsed) ?: return null
        if (metric.chapterChars <= 0) return null

        val progression = progressionFor(metric)
        val json =
            JSONObject().apply {
                put("href", link.href.toString())
                put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                put(
                    "locations",
                    JSONObject().apply {
                        put("progression", progression)
                        put("fragment", cfiString)
                    },
                )
            }
        return Locator.fromJSON(json)
    }

    fun progressionFor(metric: TextMetric): Double? {
        if (metric.chapterChars <= 0) return null
        return (metric.charOffset.toDouble() / metric.chapterChars.toDouble()).coerceIn(0.0, 1.0)
    }

    fun parsePreciseCfi(cfiString: String): ParsedPreciseCfi? {
        if (!cfiString.startsWith("epubcfi(/6/") || !cfiString.endsWith(")")) return null
        val body = cfiString.removePrefix("epubcfi(/6/").removeSuffix(")")
        val spineAndPath = body.split('!', limit = 2)
        if (spineAndPath.size != 2) return null
        val spineIndex = spineAndPath[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val rangeParts = spineAndPath[1].split(',', limit = 3)
        if (rangeParts.size < 3) return null
        val localPath = rangeParts[0].split('/').mapNotNull { it.toIntOrNull()?.takeIf { n -> n > 0 } }
        val textOffset =
            TERMINUS_OFFSET_REGEX
                .find(rangeParts[1])
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull() ?: return null
        return ParsedPreciseCfi(spineIndex, localPath, textOffset)
    }

    /** Serializes a Locator to its JSON representation. */
    fun locatorToJson(locator: Locator): String {
        val raw = locator.toJSON().toString()
        return LocatorCodec.normalizeLocatorJson(raw) ?: raw
    }

    /** Deserializes a JSON string back to a Locator. Returns null on failure. */
    fun jsonToLocator(json: String): Locator? =
        runCatching {
            val normalized = LocatorCodec.normalizeLocatorJson(json) ?: json
            Locator.fromJSON(JSONObject(normalized))
        }.getOrNull()
}
