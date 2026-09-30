package com.nexo.presentation.screen.readium

import com.nexo.debug.DebugDual
import com.nexo.debug.DebugEvent
import com.nexo.debug.DebugLog
import com.nexo.domain.model.Highlight
import com.nexo.presentation.viewmodel.CfiMigrator
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication

private const val HIGHLIGHT_OPAQUE_MASK = 0x00FFFFFF
private const val HIGHLIGHT_ALPHA_HEX = 0x66
private const val HIGHLIGHT_ALPHA_SHIFT = 24

/**
 * Maps a [Highlight] list to a [Decoration] list for Readium's
 * [org.readium.r2.navigator.DecorableNavigator.applyDecorations].
 *
 * Prefer [Highlight.locatorJson] (canonical Locator). When null/invalid, try
 * epubcfi fallback via [epubCfiFallbackLocator] using the
 * publication [readingOrder] and chapter metrics, then
 * [fallbackLocatorFromCfi]. Emits dual debug events for both applied and
 * skipped cases. Decorations are tappable Highlight style.
 *
 * Unified core — sorting, tint, text.highlight enrichment, viaFallback, DebugDual preserved.
 */
internal fun highlightsToDecorations(
    highlights: List<Highlight>,
    readingOrder: List<Link> = emptyList(),
    publication: Publication? = null,
    chapterCharsProvider: (Link) -> Int = { ESTIMATED_CHAPTER_CHARS_FALLBACK },
): List<Decoration> {
    // If publication is provided, wrap it into effective provider; otherwise use supplied provider.
    val effectiveProvider: (Link) -> Int =
        if (publication != null) {
            { link ->
                runCatching {
                    val resource = publication.get(link)
                    val bytes =
                        kotlinx.coroutines.runBlocking {
                            resource?.read()?.getOrNull()
                        }
                    val decodedLength =
                        bytes?.let {
                            try {
                                it.decodeToString().length
                            } catch (_: Throwable) {
                                it.size
                            }
                        } ?: ESTIMATED_CHAPTER_CHARS_FALLBACK
                    decodedLength.takeIf { it > 0 } ?: ESTIMATED_CHAPTER_CHARS_FALLBACK
                }.getOrDefault(ESTIMATED_CHAPTER_CHARS_FALLBACK).coerceAtLeast(1)
            }
        } else {
            chapterCharsProvider
        }

    return highlights
        .sortedBy { h ->
            h.locatorJson?.let { CfiMigrator.jsonToLocator(it)?.locations?.progression } ?: 0.0
        }.mapNotNull { h ->
            val fromJson = h.locatorJson?.let { CfiMigrator.jsonToLocator(it) }
            val viaJson = fromJson != null
            // Chain: canonical json -> precise CFI via provider metrics -> generic fallback
            val baseLocator =
                fromJson
                    ?: epubCfiFallbackLocator(h.cfiRange, readingOrder, effectiveProvider)
                    ?: fallbackLocatorFromCfi(h.cfiRange, readingOrder)
                    ?: run {
                        val reason =
                            when {
                                h.locatorJson == null && h.cfiRange.isBlank() -> "both cfi and locator null"
                                h.cfiRange.startsWith("epubcfi(") -> "epubcfi parse failed"
                                h.cfiRange.startsWith("readium:") -> "readium href not resolved"
                                else -> "fallback returned null"
                            }
                        DebugLog.warn(
                            "Highlights",
                            "highlight ${h.id} skipped: locatorJson=${h.locatorJson?.take(
                                80,
                            ) ?: "null"} cfiRange=${h.cfiRange?.take(80) ?: "null"} jsonParsed=${fromJson != null}",
                        )
                        DebugDual.log(DebugEvent.HighlightsSkipped(h.id, h.cfiRange, reason))
                        return@mapNotNull null
                    }
            // Enrich locator with text highlight when missing: Readium decoration at progression 0 alone
            // does not highlight exact phrase like "blame, Musa..."; adding Locator.Text.highlight makes
            // the decoration anchor to the concrete phrase even if progression is approximate.
            val locator =
                if (baseLocator.text?.highlight.isNullOrBlank() && h.textContent.isNotBlank()) {
                    try {
                        val json = baseLocator.toJSON()
                        // FIX: no truncar a 300 — el highlight rojo 4fd (468 chars) se cortaba en "procuraba "
                        // y Readium no anclaba el texto completo. Usar texto completo (trim) para que
                        // el anchoring por text.highlight cubra todo el rango; Readium soporta highlights
                        // largos sin problema. Si en el futuro hay highlights >3000 chars (página entera),
                        // el CFI/progression ya posiciona y el text es solo ancla secundaria.
                        val fullText = h.textContent.trim()
                        val textObj =
                            org.json.JSONObject().apply {
                                put("highlight", fullText)
                            }
                        json.put("text", textObj)
                        org.readium.r2.shared.publication.Locator
                            .fromJSON(json) ?: baseLocator
                    } catch (_: Throwable) {
                        baseLocator
                    }
                } else {
                    baseLocator
                }
            val viaFallback = !viaJson
            DebugDual.log(DebugEvent.HighlightsApplied(h.id, h.cfiRange, viaFallback))
            // Detailed debug for visibility verification: href + progression + fragment (fragment via JSON, not direct property)
            val fragmentForLog =
                runCatching {
                    locator
                        .toJSON()
                        .optJSONObject(
                            "locations",
                        )?.optString("fragment")
                        ?.take(80)
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: h.cfiRange?.take(80)
                    ?: "null"
            val fragmentShort =
                runCatching {
                    locator
                        .toJSON()
                        .optJSONObject(
                            "locations",
                        )?.optString("fragment")
                        ?.take(60)
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: h.cfiRange?.take(60)
                    ?: "null"
            DebugDual.d(
                DebugDual.TAG_SYNC,
                "highlights.applied id=${h.id} href=${locator.href} progression=${locator.locations.progression} fragment=$fragmentForLog viaFallback=$viaFallback color=${h.color}",
            )
            DebugLog.info(
                "Highlights",
                "applied id=${h.id} href=${locator.href} prog=${locator.locations.progression} frag=$fragmentShort viaFallback=$viaFallback",
            )
            val opaque =
                try {
                    android.graphics.Color.parseColor(h.color)
                } catch (_: Exception) {
                    android.graphics.Color.YELLOW
                }
            val tint = (opaque and HIGHLIGHT_OPAQUE_MASK) or (HIGHLIGHT_ALPHA_HEX shl HIGHLIGHT_ALPHA_SHIFT)
            Decoration(
                id = h.id,
                locator = locator,
                style = Decoration.Style.Highlight(tint = tint, isActive = false),
            )
        }
}

/**
 * Thin wrapper for PDF reader — delegates to unified core with empty readingOrder and no publication.
 * Keeps call-site stable; additional fallback logic is harmless when highlights already carry locatorJson.
 */
internal fun highlightsToPdfDecorations(highlights: List<Highlight>): List<Decoration> = highlightsToDecorations(highlights, emptyList(), null)
