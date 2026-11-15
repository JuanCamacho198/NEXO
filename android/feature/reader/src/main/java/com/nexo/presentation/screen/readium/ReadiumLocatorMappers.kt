package com.nexo.presentation.screen.readium

import com.nexo.debug.DebugDual
import com.nexo.debug.DebugLog
import com.nexo.presentation.viewmodel.CfiMigrator
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

internal const val ESTIMATED_CHAPTER_CHARS_FALLBACK = 10000

/**
 * Fallback Locator builder for highlights without a valid locatorJson.
 *
 * Handles:
 * - `readium:{href}` -> Locator at href start
 * - `epubcfi(...)` -> chain CfiMigrator.parsePreciseCfi / preciseCfiToLocator / migrateCfiToLocator
 *   using publication [readingOrder] to resolve href. When precise char metrics
 *   are unavailable, falls back to chapter-start with fragment=cfi so the
 *   highlight is at least visible and tappable.
 */
internal fun fallbackLocatorFromCfi(
    cfiRange: String?,
    readingOrder: List<Link> = emptyList(),
): Locator? {
    if (cfiRange == null) return null
    if (cfiRange.startsWith("readium:")) {
        val href = cfiRange.removePrefix("readium:")
        if (href.isBlank()) return null
        val json =
            org.json.JSONObject().apply {
                put("href", href)
                put("type", "application/xhtml+xml")
                put(
                    "locations",
                    org.json.JSONObject().apply {
                        put("progression", 0.0)
                        put("fragment", cfiRange)
                    },
                )
            }
        return Locator.fromJSON(json)
    }
    if (cfiRange.startsWith("epubcfi(")) {
        // Try precise CFI parse first
        val parsed = CfiMigrator.parsePreciseCfi(cfiRange)
        if (parsed != null && readingOrder.isNotEmpty()) {
            val link = readingOrder.getOrNull(parsed.spineIndex - 1)
            if (link != null) {
                // Use dummy metric when real chapterChars unavailable; still preserves fragment for re-anchoring
                val textOffset = parsed.textOffset
                val metric = CfiMigrator.TextMetric(charOffset = textOffset, chapterChars = ESTIMATED_CHAPTER_CHARS_FALLBACK)
                val progression = CfiMigrator.progressionFor(metric) ?: 0.0
                val json =
                    org.json.JSONObject().apply {
                        put("href", link.href.toString())
                        put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                        put(
                            "locations",
                            org.json.JSONObject().apply {
                                put("progression", progression)
                                put("fragment", cfiRange)
                            },
                        )
                    }
                return Locator.fromJSON(json)
            }
        }
        // Try legacy CFI path
        if (readingOrder.isNotEmpty()) {
            CfiMigrator.migrateCfiToLocator(cfiRange, readingOrder)?.let { return it }
            // Generic spine-index fallback
            val spineIndex =
                Regex("""epubcfi\(/6/(\d+)""")
                    .find(cfiRange)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            if (spineIndex != null && spineIndex > 0) {
                val link = readingOrder.getOrNull(spineIndex - 1)
                if (link != null) {
                    val json =
                        org.json.JSONObject().apply {
                            put("href", link.href.toString())
                            put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                            put(
                                "locations",
                                org.json.JSONObject().apply {
                                    put("progression", 0.0)
                                    put("fragment", cfiRange)
                                },
                            )
                        }
                    return Locator.fromJSON(json)
                }
            }
        }
        return null
    }
    return null
}

/**
 * Precise CFI fallback using publication chapter metrics.
 *
 * Tries CfiMigrator.parsePreciseCfi -> preciseCfiToLocator with readingOrder
 * and chapter metric via publication.get(link)?.read() char count. The previous
 * implementation used `bytes?.size` (byte length) and a runCatching that could
 * return null metric -> fallback to progression 0.0, leaving the orange highlight
 * invisibly at chapter start. This version decodes to string length and logs
 * href/progression/fragment for verification.
 */
internal fun epubCfiFallbackLocator(
    cfiRange: String?,
    readingOrder: List<Link>,
    publication: Publication?,
): Locator? {
    if (cfiRange == null) return null
    if (!cfiRange.startsWith("epubcfi(")) return null
    if (readingOrder.isEmpty()) return null
    // Delegate to provider seam with publication-backed provider
    return epubCfiFallbackLocator(cfiRange, readingOrder) { link ->
        runCatching {
            if (publication != null) {
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
            } else {
                ESTIMATED_CHAPTER_CHARS_FALLBACK
            }
        }.getOrDefault(ESTIMATED_CHAPTER_CHARS_FALLBACK).coerceAtLeast(1)
    }
}

/**
 * Provider seam overload — pure, no Publication I/O.
 * Caller supplies [chapterCharsProvider] returning chapter char count for a [Link].
 * Defaults to [ESTIMATED_CHAPTER_CHARS_FALLBACK] for tests without real publication.
 */
internal fun epubCfiFallbackLocator(
    cfiRange: String?,
    readingOrder: List<Link>,
    chapterCharsProvider: (Link) -> Int = { ESTIMATED_CHAPTER_CHARS_FALLBACK },
): Locator? {
    if (cfiRange == null) return null
    if (!cfiRange.startsWith("epubcfi(")) return null
    if (readingOrder.isEmpty()) return null
    // 1. Try precise CFI via CfiMigrator with provider metric
    val parsed = CfiMigrator.parsePreciseCfi(cfiRange)
    if (parsed != null) {
        val link = readingOrder.getOrNull(parsed.spineIndex - 1)
        if (link != null) {
            val precise =
                runCatching {
                    CfiMigrator.preciseCfiToLocator(cfiRange, readingOrder) { l, p ->
                        val chapterChars =
                            runCatching { chapterCharsProvider(l) }
                                .getOrDefault(ESTIMATED_CHAPTER_CHARS_FALLBACK)
                                .coerceAtLeast(1)
                        CfiMigrator.TextMetric(charOffset = p.textOffset, chapterChars = chapterChars)
                    }
                }.getOrNull()
            if (precise != null) {
                val fragLog =
                    runCatching {
                        precise
                            .toJSON()
                            .optJSONObject("locations")
                            ?.optString("fragment")
                            ?.take(80)
                    }.getOrNull()
                        ?: cfiRange.take(80)
                DebugDual.d(
                    DebugDual.TAG_SYNC,
                    "epubCfiFallback precise href=${precise.href} progression=${precise.locations.progression} fragment=$fragLog spineIndex=${parsed.spineIndex} textOffset=${parsed.textOffset}",
                )
                return precise
            } else {
                DebugLog.warn("Highlights", "epubCfiFallback precise failed for cfi=${cfiRange.take(80)} spineIndex=${parsed.spineIndex}")
            }
        }
    }
    // 2. Legacy epubcfi(/6/N) via migrateCfiToLocator
    CfiMigrator.migrateCfiToLocator(cfiRange, readingOrder)?.let {
        val fragLog =
            runCatching {
                it
                    .toJSON()
                    .optJSONObject("locations")
                    ?.optString("fragment")
                    ?.take(80)
            }.getOrNull() ?: cfiRange.take(80)
        DebugDual.d(DebugDual.TAG_SYNC, "epubCfiFallback legacy href=${it.href} progression=${it.locations.progression} fragment=$fragLog")
        return it
    }
    // 3. Generic spine-index fallback preserving fragment for tapability (progression 0.0 + fragment)
    val spineIndex =
        Regex("""epubcfi\(/6/(\d+)""")
            .find(cfiRange)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    if (spineIndex != null && spineIndex > 0) {
        val link = readingOrder.getOrNull(spineIndex - 1) ?: return null
        val json =
            org.json.JSONObject().apply {
                put("href", link.href.toString())
                put("type", link.mediaType?.toString() ?: "application/xhtml+xml")
                put(
                    "locations",
                    org.json.JSONObject().apply {
                        put("progression", 0.0)
                        put("fragment", cfiRange)
                    },
                )
            }
        val fallback = Locator.fromJSON(json)
        if (fallback != null) {
            DebugDual.d(DebugDual.TAG_SYNC, "epubCfiFallback generic href=${fallback.href} progression=0.0 fragment=${cfiRange.take(80)}")
        }
        return fallback
    }
    return null
}

// 2-arg overload required by spec signature – delegates to 3-arg with null publication
internal fun epubCfiFallbackLocator(
    cfiRange: String?,
    readingOrder: List<Link>,
): Locator? = epubCfiFallbackLocator(cfiRange, readingOrder, null as Publication?)

// Public wrapper for unit tests (keeps original public name accessible)
fun epubCfiFallbackLocatorForTest(
    cfiRange: String?,
    readingOrder: List<Link>,
): Locator? = epubCfiFallbackLocator(cfiRange, readingOrder, null)
