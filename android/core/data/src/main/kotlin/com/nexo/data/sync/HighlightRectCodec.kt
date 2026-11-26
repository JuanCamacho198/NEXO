package com.nexo.data.sync

import com.nexo.domain.model.HighlightRect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.round

/**
 * Codec for the approved cross-device `rect_json` payload (Android parity with desktop).
 *
 * Shape:
 * ```
 * {"v":1,"rects":[{"left":12.5,"top":40.0,"width":210.75,"height":14.0}, …]}
 * ```
 *
 * Rules that matter for sync:
 * - Numbers are page-relative CSS px at scale 1 and are rounded to **2 decimals on write**, so a
 *   Kotlin float and a JS double describing the same box serialise to the same value and produce
 *   no spurious outbox deltas.
 * - Nothing describing "no geometry" is written: null or empty rects encode to `null`, never to
 *   `{"v":1,"rects":[]}`. EPUB rows therefore stay untouched.
 * - Reads are tolerant. A missing/blank value, a non-object, an unknown `v`, unknown extra keys
 *   and malformed entries all resolve to what can be understood instead of throwing — a row
 *   written by a newer client must never break an older reader.
 */
object HighlightRectCodec {
    const val VERSION: Int = 1

    private const val DECIMAL_SCALE = 100.0

    /** Serialises rects for storage in the `rect_json` column or the outbox payload. */
    fun encode(rects: List<HighlightRect>?): String? = encodeToJson(rects)?.toString()

    /** Parses a stored `rect_json` string. Null, blank or unparseable input yields null. */
    fun decode(json: String?): List<HighlightRect>? {
        val raw = json?.trim()
        if (raw.isNullOrEmpty()) return null
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        return decodeFromJson(element)
    }

    /** Builds the object Supabase's jsonb column takes. */
    fun encodeToJson(rects: List<HighlightRect>?): JsonObject? {
        if (rects.isNullOrEmpty()) return null
        return buildJsonObject {
            put("v", VERSION)
            put(
                "rects",
                buildJsonArray {
                    rects.forEach { rect ->
                        add(
                            buildJsonObject {
                                put("left", roundTo2(rect.left))
                                put("top", roundTo2(rect.top))
                                put("width", roundTo2(rect.width))
                                put("height", roundTo2(rect.height))
                            },
                        )
                    }
                },
            )
        }
    }

    /** Reads a jsonb value, ignoring unknown keys and unknown schema versions. */
    fun decodeFromJson(json: JsonObject?): List<HighlightRect>? {
        val array = json?.get("rects") as? JsonArray ?: return null
        val rects =
            array.mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                HighlightRect(
                    left = obj.number("left") ?: return@mapNotNull null,
                    top = obj.number("top") ?: return@mapNotNull null,
                    width = obj.number("width") ?: return@mapNotNull null,
                    height = obj.number("height") ?: return@mapNotNull null,
                )
            }
        return rects.ifEmpty { null }
    }

    /** Re-emits a stored string as a jsonb object, normalising rounding and shape. */
    fun storedToJson(json: String?): JsonObject? = encodeToJson(decode(json))

    /** Stores a jsonb object as the column string. */
    fun jsonToStored(json: JsonObject?): String? = encode(decodeFromJson(json))

    private fun roundTo2(value: Float): Double = round(value.toDouble() * DECIMAL_SCALE) / DECIMAL_SCALE

    private fun JsonObject.number(key: String): Float? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        return primitive.doubleOrNull?.toFloat() ?: primitive.content.toFloatOrNull()
    }
}
