package com.nexo.presentation.screen.pdfjs

import com.nexo.presentation.viewmodel.reader.PdfPageSize
import com.nexo.presentation.viewmodel.reader.PdfRect
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/**
 * The selected range's viewport rect, raw from `getBoundingClientRect()`:
 * CSS px in WebView view space, top-left origin, no density factor applied.
 * Deliberately an Android-free type so the parser stays JVM-testable.
 */
data class PdfJsBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The page element's box inside the WebView viewport: CSS px, top-left origin,
 * raw from `getBoundingClientRect()`. Negative values are normal — a page
 * scrolled partly off-screen starts above the viewport.
 */
data class PdfJsPageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

sealed class PdfJsMessage {
    data class Loaded(
        val numPages: Int,
    ) : PdfJsMessage()

    data class Page(
        val page: Int,
        val total: Int,
    ) : PdfJsMessage()

    /**
     * Where the current page sits inside the WebView's viewport, and the scale its
     * page-relative rects are drawn at.
     *
     * [page] is **1-based**, like every page number PDF.js reports. [scale] is
     * `viewer.currentScale` — not 1, because the viewer fits the page to the screen
     * width — and [pageRect] is the page element's `getBoundingClientRect()`, raw
     * CSS px in viewport space, so a page-relative rect `(x, y)` paints at
     * `pageRect.left + x * scale`.
     */
    data class Viewport(
        val page: Int,
        val pageRect: PdfJsPageRect,
        val scale: Float,
    ) : PdfJsMessage()

    data class Selection(
        val empty: Boolean,
        /**
         * Raw page number as PDF.js reports it, read from the page element's
         * `data-page-number`: **1-based**. Never converted here — `null` when the
         * message carries no page (as every empty selection does), never a fake 0.
         */
        val pageNumber: Int?,
        val text: String,
        /** Page-relative, scale-1 CSS px, top-left origin — untouched payload from PDF.js. */
        val rects: List<PdfRect>,
        /** Viewport CSS px, or null when the payload has no usable `bounds`. */
        val bounds: PdfJsBounds?,
        /**
         * The start page's box at scale 1, or null when the payload has no usable
         * `pageSize`. Anything that has to map a rect back to the start page's
         * coordinate space needs it; without it, no rect can be ruled out.
         */
        val pageSize: PdfPageSize? = null,
        /** PDF.js `viewer.currentScale`, defaults to 1 when absent or nonsensical. */
        val scale: Float,
    ) : PdfJsMessage() {
        /** Character count for the dispatch log line — the prose itself must never be logged. */
        val textLength: Int
            get() = text.length
    }

    data class Error(
        val message: String,
    ) : PdfJsMessage()
}

fun parsePdfJsMessage(raw: String?): PdfJsMessage? {
    if (raw == null) return null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    return when (json.optString("type")) {
        "loaded" -> PdfJsMessage.Loaded(json.optInt("numPages"))
        "page" -> PdfJsMessage.Page(json.optInt("page"), json.optInt("total"))
        "selection" -> parsePdfJsSelection(json)
        "viewport" -> parsePdfJsViewport(json)
        "error" -> PdfJsMessage.Error(json.optString("message"))
        else -> null
    }
}

private fun parsePdfJsSelection(json: JSONObject): PdfJsMessage.Selection {
    // A non-string `text` is treated exactly like a missing one, so a wrong type
    // degrades to "nothing selected" instead of a coerced "123" selection.
    val text = (json.opt("text") as? String).orEmpty()
    return PdfJsMessage.Selection(
        empty = json.optBoolean("empty", false) || text.isBlank(),
        pageNumber = json.optInt("pageNumber", 0).takeIf { it >= 1 },
        text = text,
        rects = parsePdfJsRects(json.optJSONArray("rects")),
        bounds = parsePdfJsBounds(json),
        pageSize = parsePdfJsPageSize(json),
        scale = json.optDouble("scale", 1.0).takeIf { it.isFinite() && it > 0.0 }?.toFloat() ?: 1f,
    )
}

/**
 * Where the current page sits and at what scale, or null when the payload cannot
 * be painted with.
 *
 * Unlike a selection's `scale` (which defaults to 1, because a selection only uses
 * it to normalise its own rects), a viewport with no usable scale is useless:
 * painting with a guessed 1 would place every highlight by the page-width factor
 * off. A dropped message leaves the last good viewport in place.
 */
private fun parsePdfJsViewport(json: JSONObject): PdfJsMessage.Viewport? {
    val page = json.optInt("page", 0).takeIf { it >= 1 } ?: return null
    val raw = json.optJSONObject("pageRect") ?: return null
    val left = raw.finiteFloat("left") ?: return null
    val top = raw.finiteFloat("top") ?: return null
    val width = raw.finiteFloat("width") ?: return null
    val height = raw.finiteFloat("height") ?: return null
    if (width <= 0f || height <= 0f) return null
    val scale = json.optDouble("scale", Double.NaN).takeIf { it.isFinite() && it > 0.0 }?.toFloat() ?: return null
    return PdfJsMessage.Viewport(page, PdfJsPageRect(left, top, width, height), scale)
}

/** Reads a numeric member, or null when it is missing, non-numeric, null or not finite. */
private fun JSONObject.finiteFloat(key: String): Float? = optDouble(key, Double.NaN).takeIf { it.isFinite() }?.toFloat()

/**
 * One entry per client rect. A malformed entry drops only itself: PDF.js already
 * filters zero-area rects, so a non-positive size here means a corrupt payload.
 */
private fun parsePdfJsRect(entry: JSONObject?): PdfRect? {
    if (entry == null) return null
    val x = entry.finiteFloat("x") ?: return null
    val y = entry.finiteFloat("y") ?: return null
    val width = entry.finiteFloat("width") ?: return null
    val height = entry.finiteFloat("height") ?: return null
    if (width <= 0f || height <= 0f) return null
    return PdfRect(x, y, width, height)
}

private fun parsePdfJsRects(array: JSONArray?): List<PdfRect> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index -> parsePdfJsRect(array.optJSONObject(index)) }
}

private fun parsePdfJsBounds(json: JSONObject): PdfJsBounds? {
    val raw = json.optJSONObject("bounds") ?: return null
    val left = raw.finiteFloat("left") ?: return null
    val top = raw.finiteFloat("top") ?: return null
    val right = raw.finiteFloat("right") ?: return null
    val bottom = raw.finiteFloat("bottom") ?: return null
    // A range spanning CSS columns can come back inverted; normalize at the
    // boundary so no downstream RectF has to defend against bottom < top.
    return PdfJsBounds(
        left = minOf(left, right),
        top = minOf(top, bottom),
        right = maxOf(left, right),
        bottom = maxOf(top, bottom),
    )
}

/** The start page's box at scale 1, as PDF.js measures it. A half-known box is dropped whole. */
private fun parsePdfJsPageSize(json: JSONObject): PdfPageSize? {
    val raw = json.optJSONObject("pageSize") ?: return null
    val width = raw.finiteFloat("width") ?: return null
    val height = raw.finiteFloat("height") ?: return null
    if (width <= 0f || height <= 0f) return null
    return PdfPageSize(width, height)
}

fun pdfPageFromLocator(locator: Locator): Int? =
    (
        locator.locations.position
            ?: locator.locations.fragments
                .firstNotNullOfOrNull {
                    it
                        .removePrefix("#")
                        .takeIf { f -> f.startsWith("page=") }
                        ?.removePrefix("page=")
                        ?.toIntOrNull()
                }
    )?.takeIf { it >= 1 }
