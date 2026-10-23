package com.nexo.data.remote.addons.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Addon payload parsing: pure, no I/O.
 *
 * Every payload is untrusted. The parsers are total: malformed input yields an
 * empty result with the rejected unit counted in `skipped`, never a throw.
 *
 * [AddonDeliveryGuard.assertDeliverableStream] is the single place a stream is
 * accepted or rejected; this module never decides that on its own.
 */
enum class StreamSource(
    val wire: String,
) {
    DOWNLOAD("download"),
    EXTERNAL("external"),
}

enum class StreamAccessType(
    val wire: String,
) {
    PUBLIC_DOMAIN("public-domain"),
    BORROW("borrow"),
    PURCHASE("purchase"),
    UNKNOWN("unknown"),
}

enum class StreamFormat(
    val wire: String,
) {
    EPUB("epub"),
    PDF("pdf"),
}

data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val authors: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val isbn13: String? = null,
)

data class BookStream(
    val source: StreamSource,
    val url: String,
    val accessType: StreamAccessType,
    val format: StreamFormat,
    val addonId: String? = null,
    val name: String? = null,
    val title: String? = null,
    /**
     * Addon install time in epoch milliseconds, stamped by the caller. It is the
     * third ordering tier, so it rides on the stream: the ordering function never
     * reads the registry.
     */
    val addedAt: Long? = null,
)

data class CatalogPayload(
    val metas: List<MetaPreview>,
    val skipped: Int,
)

data class StreamPayload(
    val streams: List<BookStream>,
    val skipped: Int,
)

/** Readers for untrusted payload JSON, shared by the envelope and shape parsers. */
internal object AddonPayloadJson {
    const val MAX_STRING_CHARS = 2048

    fun truncate(value: String): String = if (value.length > MAX_STRING_CHARS) value.substring(0, MAX_STRING_CHARS) else value

    /** A non-empty string, truncated rather than rejected. */
    fun readString(value: Any?): String? {
        if (value !is String || value.isEmpty()) return null
        return truncate(value)
    }

    /**
     * Reads a string list, tolerating the two shapes addons send: bare strings and
     * `{ name }` objects. Unreadable entries are dropped, not fatal.
     */
    fun readStringArray(value: Any?): List<String> {
        if (value !is JSONArray) return emptyList()
        val entries = mutableListOf<String>()
        for (index in 0 until value.length()) {
            val entry = value.opt(index)
            val text =
                when {
                    entry is String -> readString(entry)
                    entry is JSONObject -> readString(entry.opt("name"))
                    else -> null
                }
            if (text != null) entries += text
        }
        return entries
    }

    fun isInteger(value: Any?): Boolean =
        when (value) {
            is Int, is Long, is Short, is Byte -> true
            is Double -> value.isFinite() && value == Math.floor(value)
            is Float -> value.isFinite() && value == Math.floor(value.toDouble())
            else -> false
        }

    /** A positive integral number as `Long`, or `null` — the shape adapters' id rule. */
    fun positiveInteger(value: Any?): Long? {
        if (!isInteger(value)) return null
        val number = (value as Number).toLong()
        return if (number > 0) number else null
    }

    fun integerText(value: Any?): String? = if (isInteger(value)) (value as Number).toLong().toString() else null
}

object AddonPayloads {
    private const val MAX_METAS = 200
    private const val MAX_STREAMS = 100

    private const val GUTENBERG_ID_PREFIX = "gutenberg:"
    private const val OPENLIBRARY_ID_PREFIX = "openlibrary:"
    private const val ISBN_PREFIX = "isbn:"
    private const val COVER_FORMAT = "image/jpeg"
    private const val COVER_URL_PREFIX = "https://covers.openlibrary.org/b/id/"
    private const val COVER_URL_SUFFIX = "-M.jpg"

    private val ISBN13 = Regex("^\\d{13}$")
    private val DIGITS = Regex("^\\d+$")

    private val ACCESS_RANK =
        mapOf(
            StreamAccessType.PUBLIC_DOMAIN to 0,
            StreamAccessType.BORROW to 1,
            StreamAccessType.PURCHASE to 2,
            StreamAccessType.UNKNOWN to 3,
        )

    private val FORMAT_RANK = mapOf(StreamFormat.EPUB to 0, StreamFormat.PDF to 1)

    /** An unstamped addon sorts after every stamped one. */
    private const val UNSTAMPED_ADDON_ADDED_AT = Long.MAX_VALUE

    // ------------------------------------------------------------------ catalog

    /** `isbn13` from the field, else derived from an `isbn:` id prefix. */
    private fun readIsbn13(
        value: Any?,
        id: String,
    ): String? {
        AddonPayloadJson.readString(value)?.let { return it }
        if (!id.startsWith(ISBN_PREFIX)) return null
        val derived = id.removePrefix(ISBN_PREFIX)
        return if (ISBN13.matches(derived)) derived else null
    }

    /** The envelope list a resource answers with, or `null` when there is none. */
    private fun readEnvelopeList(
        body: Any?,
        key: String,
    ): JSONArray? {
        if (body !is JSONObject) return null
        return body.opt(key) as? JSONArray
    }

    private fun toMetaPreview(value: Any?): MetaPreview? {
        if (value !is JSONObject) return null
        val id = AddonPayloadJson.readString(value.opt("id")) ?: return null
        val name = AddonPayloadJson.readString(value.opt("name")) ?: return null
        return MetaPreview(
            id = id,
            type = AddonPayloadJson.readString(value.opt("type")) ?: AddonProtocolConstants.TYPE_BOOK,
            name = name,
            poster = AddonPayloadJson.readString(value.opt("poster")),
            description = AddonPayloadJson.readString(value.opt("description")),
            releaseInfo = AddonPayloadJson.readString(value.opt("releaseInfo")),
            authors = AddonPayloadJson.readStringArray(value.opt("authors")),
            languages = AddonPayloadJson.readStringArray(value.opt("languages")),
            subjects = AddonPayloadJson.readStringArray(value.opt("subjects")),
            isbn13 = readIsbn13(value.opt("isbn13"), id),
        )
    }

    private fun capMetas(
        candidates: List<MetaPreview>,
        skipped: Int,
    ): CatalogPayload =
        if (candidates.size <= MAX_METAS) {
            CatalogPayload(candidates, skipped)
        } else {
            CatalogPayload(candidates.take(MAX_METAS), skipped + (candidates.size - MAX_METAS))
        }

    /**
     * `shape` selects the named adapter. `"raw"` is the Stremio envelope; a shape
     * this build ships no adapter for (`legacy-v2`) contributes nothing rather
     * than failing the fan-out.
     */
    fun parseCatalogPayload(
        body: Any?,
        shape: AddonShape,
    ): CatalogPayload {
        if (shape == AddonShape.GUTENDEX) {
            val shaped = shapeGutendexCatalog(body)
            return capMetas(shaped.metas, shaped.skipped)
        }
        if (shape == AddonShape.OPENLIBRARY) {
            val shaped = shapeOpenlibraryCatalog(body)
            return capMetas(shaped.metas, shaped.skipped)
        }
        if (shape != AddonShape.RAW) return CatalogPayload(emptyList(), 0)

        val entries = readEnvelopeList(body, "metas") ?: return CatalogPayload(emptyList(), 1)
        val metas = mutableListOf<MetaPreview>()
        var skipped = 0
        for (index in 0 until entries.length()) {
            val meta = toMetaPreview(entries.opt(index))
            if (meta == null || metas.size >= MAX_METAS) {
                skipped += 1
                continue
            }
            metas += meta
        }
        return CatalogPayload(metas, skipped)
    }

    fun parseMetaPayload(
        body: Any?,
        shape: AddonShape,
    ): MetaPreview? {
        if (shape == AddonShape.GUTENDEX) return shapeGutendexMeta(body)
        if (shape == AddonShape.OPENLIBRARY) return shapeOpenlibraryMeta(body)
        if (shape != AddonShape.RAW) return null
        if (body !is JSONObject) return null
        return toMetaPreview(body.opt("meta"))
    }

    // ------------------------------------------------------------------ streams

    fun parseStreamPayload(body: Any?): StreamPayload {
        val entries = readEnvelopeList(body, "streams") ?: return StreamPayload(emptyList(), 1)

        val streams = mutableListOf<BookStream>()
        var skipped = 0
        for (index in 0 until entries.length()) {
            val stream = AddonDeliveryGuard.assertDeliverableStream(entries.opt(index))
            if (stream == null || streams.size >= MAX_STREAMS) {
                skipped += 1
                continue
            }
            streams += stream
        }
        return StreamPayload(streams, skipped)
    }

    // ----------------------------------------------------------------- ordering

    private fun compareAddonIds(
        left: String?,
        right: String?,
    ): Int = (left ?: "").compareTo(right ?: "")

    private val COMPARE_STREAMS =
        Comparator<BookStream> { left, right ->
            val byAccess = ACCESS_RANK.getValue(left.accessType) - ACCESS_RANK.getValue(right.accessType)
            if (byAccess != 0) {
                byAccess
            } else {
                val byFormat = FORMAT_RANK.getValue(left.format) - FORMAT_RANK.getValue(right.format)
                if (byFormat != 0) {
                    byFormat
                } else {
                    val byAddedAt =
                        (left.addedAt ?: UNSTAMPED_ADDON_ADDED_AT).compareTo(right.addedAt ?: UNSTAMPED_ADDON_ADDED_AT)
                    if (byAddedAt != 0) byAddedAt else compareAddonIds(left.addonId, right.addonId)
                }
            }
        }

    /**
     * The product order of the option list, not install order: `public-domain` →
     * `borrow` → `purchase` → `unknown`, then `epub` → `pdf`, then addon
     * `addedAt`, then addon `id`. Stable, so ties keep the fan-out order.
     */
    fun orderStreams(streams: List<BookStream>): List<BookStream> = streams.sortedWith(COMPARE_STREAMS)

    // ------------------------------------------------------------------ gutendex

    /** Gutendex ids are integers; a numeric string is accepted as the same id. */
    private fun readGutenbergId(value: Any?): String? {
        AddonPayloadJson.positiveInteger(value)?.let { return "$GUTENBERG_ID_PREFIX$it" }
        if (value is String && DIGITS.matches(value)) return "$GUTENBERG_ID_PREFIX$value"
        return null
    }

    private fun shapeGutendexMeta(body: Any?): MetaPreview? {
        if (body !is JSONObject) return null
        val id = readGutenbergId(body.opt("id")) ?: return null
        val name = AddonPayloadJson.readString(body.opt("title")) ?: return null
        val formats = body.opt("formats")
        val poster = if (formats is JSONObject) AddonPayloadJson.readString(formats.opt(COVER_FORMAT)) else null
        return MetaPreview(
            id = id,
            type = AddonProtocolConstants.TYPE_BOOK,
            name = name,
            poster = poster,
            authors = AddonPayloadJson.readStringArray(body.opt("authors")),
            languages = AddonPayloadJson.readStringArray(body.opt("languages")),
            subjects = AddonPayloadJson.readStringArray(body.opt("subjects")),
        )
    }

    private fun shapeGutendexCatalog(body: Any?): CatalogPayload {
        if (body !is JSONObject || body.opt("results") !is JSONArray) return CatalogPayload(emptyList(), 1)
        val results = body.opt("results") as JSONArray

        val metas = mutableListOf<MetaPreview>()
        var skipped = 0
        for (index in 0 until results.length()) {
            val meta = shapeGutendexMeta(results.opt(index))
            if (meta == null) {
                skipped += 1
                continue
            }
            metas += meta
        }
        return CatalogPayload(metas, skipped)
    }

    // -------------------------------------------------------------- openlibrary

    /** `/works/OL66554W` and `OL66554W` both become `openlibrary:OL66554W`. */
    private fun readWorkId(value: Any?): String? {
        val key = AddonPayloadJson.readString(value) ?: return null
        val segment = key.split('/').lastOrNull { it.isNotEmpty() } ?: return null
        return "$OPENLIBRARY_ID_PREFIX$segment"
    }

    /** Old records carry a plain string, new ones `{ type, value }`. */
    private fun readDescription(value: Any?): String? =
        when (value) {
            is String -> AddonPayloadJson.readString(value)
            is JSONObject -> AddonPayloadJson.readString(value.opt("value"))
            else -> null
        }

    private fun readReleaseInfo(
        year: Any?,
        date: Any?,
    ): String? = AddonPayloadJson.integerText(year) ?: AddonPayloadJson.readString(date)

    private fun readCover(
        coverId: Any?,
        covers: Any?,
    ): String? {
        val candidate: Long? =
            AddonPayloadJson.positiveInteger(coverId)
                ?: (covers as? JSONArray)?.let { AddonPayloadJson.positiveInteger(it.opt(0)) }
        return candidate?.let { "$COVER_URL_PREFIX$it$COVER_URL_SUFFIX" }
    }

    private fun readIsbn13(value: Any?): String? {
        if (value !is JSONArray) return null
        for (index in 0 until value.length()) {
            val entry = value.opt(index)
            if (entry is String && ISBN13.matches(entry)) return entry
        }
        return null
    }

    private fun shapeOpenlibraryMeta(body: Any?): MetaPreview? {
        if (body !is JSONObject) return null
        val id = readWorkId(body.opt("key")) ?: return null
        val name = AddonPayloadJson.readString(body.opt("title")) ?: return null
        val subjects =
            if (body.has("subjects")) {
                AddonPayloadJson.readStringArray(body.opt("subjects"))
            } else {
                AddonPayloadJson.readStringArray(body.opt("subject"))
            }
        return MetaPreview(
            id = id,
            type = AddonProtocolConstants.TYPE_BOOK,
            name = name,
            poster = readCover(body.opt("cover_i"), body.opt("covers")),
            description = readDescription(body.opt("description")),
            releaseInfo = readReleaseInfo(body.opt("first_publish_year"), body.opt("first_publish_date")),
            authors = AddonPayloadJson.readStringArray(body.opt("author_name")),
            languages = AddonPayloadJson.readStringArray(body.opt("language")),
            subjects = subjects,
            isbn13 = readIsbn13(body.opt("isbn")),
        )
    }

    private fun shapeOpenlibraryCatalog(body: Any?): CatalogPayload {
        if (body !is JSONObject || body.opt("docs") !is JSONArray) return CatalogPayload(emptyList(), 1)
        val docs = body.opt("docs") as JSONArray

        val metas = mutableListOf<MetaPreview>()
        var skipped = 0
        for (index in 0 until docs.length()) {
            val meta = shapeOpenlibraryMeta(docs.opt(index))
            if (meta == null) {
                skipped += 1
                continue
            }
            metas += meta
        }
        return CatalogPayload(metas, skipped)
    }
}
