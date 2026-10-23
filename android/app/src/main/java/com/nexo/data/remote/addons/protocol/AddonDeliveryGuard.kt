package com.nexo.data.remote.addons.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The only decision point for a stream. Returns `null` — never throws — for:
 * an `infoHash`, `behaviorHints.bittorrent`, a `sources` entry of type
 * `torrent`, neither `url` nor `externalUrl`, or a link that is not HTTP(S).
 *
 * Torrents are dropped here and nowhere else, so no later layer can
 * reintroduce one.
 */
object AddonDeliveryGuard {
    private val HTTP_PROTOCOLS = setOf("http", "https")

    fun assertDeliverableStream(raw: Any?): BookStream? {
        if (raw !is JSONObject) return null

        // A torrent identity makes the entry undeliverable whatever else it carries.
        if (raw.has("infoHash")) return null

        val rawHints = raw.opt("behaviorHints")
        val hints = if (rawHints is JSONObject) rawHints else null
        if (hints != null && hints.has("bittorrent")) return null

        // A source set can point at a magnet link without an `infoHash` field.
        val sources = raw.opt("sources")
        if (sources is JSONArray && hasTorrentSource(sources)) return null

        val url = AddonPayloadJson.readString(raw.opt("url"))
        val externalUrl = AddonPayloadJson.readString(raw.opt("externalUrl"))
        val source = if (url != null) StreamSource.DOWNLOAD else StreamSource.EXTERNAL
        val target = url ?: externalUrl ?: return null
        if (!isHttpUrl(target)) return null

        return BookStream(
            source = source,
            url = target,
            accessType = readAccessType(hints),
            format = readFormat(hints, target),
            name = AddonPayloadJson.readString(raw.opt("name")),
            title = AddonPayloadJson.readString(raw.opt("title")),
        )
    }

    /** Torrent identity under a non-`infoHash` name. */
    private fun hasTorrentSource(sources: JSONArray): Boolean =
        (0 until sources.length()).any { index ->
            val entry = sources.opt(index)
            entry is JSONObject && (entry.opt("type") as? String)?.trim()?.lowercase() == "torrent"
        }

    /**
     * A payload may only be a link a browser can open. `magnet:` and `data:` are
     * torrent and script vectors, not books, so they never reach the option list.
     */
    private fun isHttpUrl(value: String): Boolean {
        val uri =
            try {
                URI(value)
            } catch (_: Exception) {
                return false
            }
        val scheme = uri.scheme ?: return false
        return HTTP_PROTOCOLS.contains(scheme.lowercase())
    }

    private fun readAccessType(hints: JSONObject?): StreamAccessType {
        val declared = hints?.opt("accessType")
        if (declared !is String) return StreamAccessType.UNKNOWN
        return StreamAccessType.entries.firstOrNull { it.wire == declared } ?: StreamAccessType.UNKNOWN
    }

    private fun readFormat(
        hints: JSONObject?,
        url: String,
    ): StreamFormat {
        val declared = hints?.opt("format")
        if (declared is String) {
            StreamFormat.entries.firstOrNull { it.wire == declared }?.let { return it }
        }
        return inferFormat(url)
    }

    /** Path extension, ignoring query and fragment. Any other extension is `epub`. */
    private fun inferFormat(url: String): StreamFormat {
        val path = url.substringBefore('?').substringBefore('#')
        return if (path.lowercase().endsWith(".pdf")) StreamFormat.PDF else StreamFormat.EPUB
    }
}
