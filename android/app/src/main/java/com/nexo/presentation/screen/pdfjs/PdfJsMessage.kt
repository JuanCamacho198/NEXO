package com.nexo.presentation.screen.pdfjs

import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

sealed class PdfJsMessage {
    data class Loaded(
        val numPages: Int,
    ) : PdfJsMessage()

    data class Page(
        val page: Int,
        val total: Int,
    ) : PdfJsMessage()

    data class Selection(
        val empty: Boolean,
        val pageNumber: Int,
        val textLength: Int,
    ) : PdfJsMessage()

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
        "selection" ->
            PdfJsMessage.Selection(
                empty = json.optBoolean("empty"),
                pageNumber = json.optInt("pageNumber"),
                textLength = json.optString("text").length,
            )
        "error" -> PdfJsMessage.Error(json.optString("message"))
        else -> null
    }
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
