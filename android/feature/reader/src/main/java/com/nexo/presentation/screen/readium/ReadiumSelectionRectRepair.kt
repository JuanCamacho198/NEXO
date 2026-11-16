package com.nexo.presentation.screen.readium

import android.graphics.RectF
import org.json.JSONObject

/**
 * Recovers a usable selection rect from Readium's corrupted one.
 *
 * ## The upstream defect
 *
 * `readium-navigator` 3.2.0 returns a selection rect that is BOTH the wrong
 * shape and in the wrong place. The cause is a single line in the dependency:
 *
 * ```
 * // EpubNavigatorFragment.kt (3.2.0)
 * private fun RectF.adjustedToViewport(): RectF =
 *     currentReflowablePageFragment?.paddingTop?.let { topOffset ->
 *         RectF(left, top + topOffset, right, bottom)   // <-- top ONLY
 *     } ?: this
 * ```
 *
 * The rect it receives from `window.readium.getCurrentSelection()` is already
 * correct (device pixels, produced by the asset's symmetric `V()`: every edge
 * is scaled by `devicePixelRatio`). `adjustedToViewport` then adds the WebView
 * container's `paddingTop` to `top` and leaves `bottom` alone. Because NEXO
 * hosts the reader inside a Compose `Scaffold` that has already consumed the
 * status-bar inset, that `paddingTop` is large, and adding it to one edge only:
 *
 * - **inverts** the rect (`top` ends up below `bottom`), and
 * - stretches it by exactly `paddingTop` (`height = lineHeight + paddingTop`),
 *   while also shifting where it sits.
 *
 * Measured on `El extranjero`, word `de` (dpr 2.625, reader inset 128px):
 * the raw JS rect is `[top=786, bottom=838]` (a correct 52px line box);
 * Readium delivers `top = 786 + 236 = 1022, bottom = 838`. The consumer then
 * sees an 184px-tall rect whose top is 182px too high and whose bottom is 52px
 * too high. The two shifts differ, so it is NOT a constant offset — a magic
 * number would fix this line and break every other one.
 *
 * ## Why the repair is structural
 *
 * Given only the corrupted rect, the padding cannot be recovered in general
 * (`min`/`max` throws away which edge was padded). The honest fix is to stop
 * consuming the corrupted value: [selectionRectJs] asks the page for the SAME
 * unpadded rect Readium itself starts from. That value is symmetric and correct
 * for a selection anywhere on the page — single-line, wrapped, at any zoom or
 * font size — because no padding is involved in producing it.
 *
 * [repairTopOnlyPadding] is the fallback for when the raw query is unavailable
 * (e.g. the JavaScript interface is not ready on the first poll). It undoes the
 * defect exactly when the rect is provably corrupted, and is a no-op on a
 * well-formed rect.
 */
object ReadiumSelectionRectRepair {
    /**
     * How many line heights a rect may span before the repair treats it as
     * carrying Readium's padding rather than being a genuine selection.
     *
     * A wrapped selection is a small multiple of the line box. Readium's defect
     * adds the whole WebView `paddingTop` (measured 236px on the dev_clean AVD)
     * on top of the line, so the corrupted rect is far past any plausible
     * wrapped selection. 1.6 leaves room for descenders and multi-line picks
     * while still separating the two cases by a wide margin.
     */
    private const val MULTI_LINE_TOLERANCE = 1.6f

    /**
     * JavaScript returning the raw, unpadded selection rect in device pixels.
     *
     * This is the same entry point the dependency uses
     * (`readium.getCurrentSelection()`), read one level lower: the `rect` field
     * before `adjustedToViewport()` touches it. Returns `null` JSON when there
     * is no selection, so the caller can fall back.
     */
    const val selectionRectJs: String =
        "(function(){try{" +
            "var s=window.readium&&window.readium.getCurrentSelection?window.readium.getCurrentSelection():null;" +
            "if(!s||!s.rect)return 'null';" +
            "var r=s.rect;" +
            "return JSON.stringify({left:r.left,top:r.top,right:r.right,bottom:r.bottom});" +
            "}catch(e){return 'null';}})()"

    /**
     * Parses the [selectionRectJs] payload into an ordered [RectF].
     *
     * The payload crosses `evaluateJavascript` as a JSON-encoded string, so it
     * arrives quoted (`"{\"left\":...}"`). Returns `null` for the `null`
     * sentinel, malformed JSON, or a non-finite edge — never a partial rect.
     */
    fun parseRawRect(jsResult: String?): RectF? {
        val trimmed = jsResult?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null") return null
        // evaluateJavascript wraps the string result in quotes; unwrap once.
        val payload =
            trimmed
                .removeSurrounding("\"")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        if (payload.isEmpty() || payload == "null") return null
        return try {
            val json = JSONObject(payload)
            val left = json.optDouble("left", Double.NaN)
            val top = json.optDouble("top", Double.NaN)
            val right = json.optDouble("right", Double.NaN)
            val bottom = json.optDouble("bottom", Double.NaN)
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
                null
            } else {
                RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Undoes Readium's `top`-only padding on a rect that is provably corrupted.
     *
     * A rect is treated as corrupted only when it is inverted or absurdly tall
     * for a text line — the two signatures the defect produces. In that case the
     * true line shares the corrupted rect's `bottom` (that edge was never
     * touched), so the repaired rect is the line of [lineHeightPx] ending at
     * `bottom`. Otherwise the input is returned unchanged, so a well-formed rect
     * from a future Readium version is never mangled.
     *
     * This cannot invent a position: it only narrows a rect that is already
     * anchored at the correct `bottom`.
     */
    fun repairTopOnlyPadding(
        rect: RectF,
        lineHeightPx: Float,
    ): RectF {
        val top = rect.top
        val bottom = rect.bottom
        val height = bottom - top
        val inverted = top > bottom
        // A single text line is never many multiples of a line height. Anything
        // past a small tolerance carries the padding defect's signature.
        val tooTall = height > lineHeightPx * MULTI_LINE_TOLERANCE

        if (!inverted && !tooTall) return rect
        if (lineHeightPx <= 0f) return rect

        // 'bottom' survived the tool; the true line ends there.
        return RectF(rect.left, bottom - lineHeightPx, rect.right, bottom)
    }
}
