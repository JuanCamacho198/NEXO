package com.nexo.data.remote.wiktionary

import com.nexo.domain.definition.NEXO_WIKTIONARY_USER_AGENT
import com.nexo.domain.model.WiktionaryErrorCode
import com.nexo.domain.model.WiktionaryFetch
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The MediaWiki `action=parse` response envelope, narrowed to the one field the
 * parser needs.
 *
 * Only `parse.wikitext["*"]` is mapped; everything else is ignored so upstream
 * additions never break decoding. A page that does not exist (or a malformed
 * request) answers 200 with a top-level `error` object and no `parse`, which
 * decodes here as a null [parse] — the caller reads that as [WiktionaryFetch.MissingPage].
 */
@Serializable
internal data class WiktionaryParseResponse(
    @SerialName("parse")
    val parse: WiktionaryParse? = null,
    @SerialName("error")
    val error: WiktionaryApiError? = null,
)

@Serializable
internal data class WiktionaryParse(
    @SerialName("title")
    val title: String? = null,
    @SerialName("wikitext")
    val wikitext: WiktionaryWikitext? = null,
)

@Serializable
internal data class WiktionaryWikitext(
    @SerialName("*")
    val value: String? = null,
)

@Serializable
internal data class WiktionaryApiError(
    @SerialName("code")
    val code: String? = null,
    @SerialName("info")
    val info: String? = null,
)

/**
 * The raw wikitext of [word] on the [wikiLanguage] Wiktionary, or a terminal
 * [WiktionaryFetch].
 *
 * Direct-from-app, no API key. It is TOTAL across its boundary: network
 * failures, HTTP errors, malformed JSON and empty bodies all resolve to a
 * [WiktionaryFetch] — it never throws into the cascade.
 *
 * A transport failure and a missing page are DIFFERENT results on purpose: the
 * cascade must be able to distinguish "this wiki does not have the word" from
 * "this wiki did not answer", so a dead source can be reported honestly rather
 * than as a "no definition".
 */
class WiktionaryClient(
    private val transport: WiktionaryHttpTransport,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    /** Fetches the raw wikitext of [word] on the [wikiLanguage] edition. */
    suspend fun fetchWikitext(
        word: String,
        wikiLanguage: String,
    ): WiktionaryFetch {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) {
            return WiktionaryFetch.Failure(WiktionaryErrorCode.PARSE_ERROR, "empty word")
        }
        val url = wiktionaryEndpoint(wikiLanguage, trimmed)
        if (!url.startsWith("https://")) {
            return WiktionaryFetch.Failure(WiktionaryErrorCode.HTTPS_REQUIRED, "endpoint must be https")
        }
        val response =
            try {
                transport.get(url)
            } catch (_: Throwable) {
                return WiktionaryFetch.Failure(WiktionaryErrorCode.NETWORK, "transport threw")
            }
        return extractWikitext(response.status, response.body, json)
    }
}

/**
 * Parses a raw MediaWiki HTTP response into a [WiktionaryFetch].
 *
 * Pure and total: every failure mode (non-200 HTTP, an `error` envelope,
 * unparseable body, a missing `parse.wikitext`) maps to a distinct terminal
 * value — it never throws.
 */
internal fun extractWikitext(
    httpStatus: Int,
    body: String,
    json: Json,
): WiktionaryFetch {
    if (httpStatus !in HTTP_SUCCESS_RANGE) {
        return WiktionaryFetch.Failure(WiktionaryErrorCode.HTTP_ERROR, "HTTP $httpStatus")
    }
    if (body.isBlank()) {
        return WiktionaryFetch.Failure(WiktionaryErrorCode.PARSE_ERROR, "empty body")
    }
    val parsed =
        try {
            json.decodeFromString(WiktionaryParseResponse.serializer(), body)
        } catch (_: Exception) {
            return WiktionaryFetch.Failure(WiktionaryErrorCode.PARSE_ERROR, "malformed response")
        }
    if (parsed.parse == null) {
        // A top-level `error` (missingtitle, invalidtitle) is the API's way of
        // saying the page does not exist. Either way: no wikitext, no hit.
        return WiktionaryFetch.MissingPage
    }
    val wikitext = parsed.parse.wikitext?.value
    if (wikitext.isNullOrEmpty()) {
        return WiktionaryFetch.MissingPage
    }
    return WiktionaryFetch.Success(wikitext)
}

/** HTTP 2xx — the MediaWiki API answers 200 on success and error bodies alike. */
private val HTTP_SUCCESS_RANGE = 200..299

/**
 * Production transport over the injected Ktor client (OkHttp engine, matching
 * the translation/catalog clients). Sends the identified UA Wikimedia requires —
 * a generic or missing agent is blocked with a 403.
 */
class KtorWiktionaryHttpTransport(
    private val client: HttpClient,
    private val userAgent: String = NEXO_WIKTIONARY_USER_AGENT,
) : WiktionaryHttpTransport {
    override suspend fun get(url: String): WiktionaryHttpResponse {
        val response =
            client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json")
            }
        return WiktionaryHttpResponse(response.status.value, response.bodyAsText())
    }
}
