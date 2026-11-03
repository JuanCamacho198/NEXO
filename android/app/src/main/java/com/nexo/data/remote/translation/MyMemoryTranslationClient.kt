package com.nexo.data.remote.translation

import com.nexo.domain.translation.TranslationPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

/**
 * Direct-from-app MyMemory client. No API key: MyMemory is used anonymously
 * (5.000 chars/day per IP), so there is no secret to protect and no Edge
 * Function is needed. Each user burns their own quota from their own IP.
 *
 * The client is total across its boundary: network failures, HTTP errors,
 * malformed JSON, empty payloads and echoes all resolve to a
 * [TranslationResult] — it never throws into the UI layer.
 */
class MyMemoryTranslationClient(
    private val transport: TranslationHttpTransport,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    /**
     * Translates [text] from [sourceLanguage] to [targetLanguage].
     *
     * @param sourceLanguage BCP-47 or ISO-639-1 tag; normalised to its base.
     * @param targetLanguage BCP-47 or ISO-639-1 tag; normalised to its base.
     */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): TranslationResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return TranslationResult.Failure(TranslationErrorCode.EMPTY_RESPONSE, "empty query")
        }
        val source = TranslationPolicy.normalizeLanguage(sourceLanguage)
        val target = TranslationPolicy.normalizeLanguage(targetLanguage)
        if (source == target) {
            return TranslationResult.Failure(
                TranslationErrorCode.NO_LANGPAIR,
                "source and target are the same ($source)",
            )
        }
        val langPair = TranslationPolicy.langPair(source, target)
        val url =
            buildString {
                append(MYMEMORY_BASE_URL)
                append("?q=")
                append(encode(trimmed))
                append("&langpair=")
                append(encode(langPair))
            }
        val response =
            try {
                transport.get(url)
            } catch (_: Throwable) {
                return TranslationResult.NetworkError
            }
        return parseMyMemoryResponse(
            httpStatus = response.status,
            body = response.body,
            original = trimmed,
            json = json,
        )
    }

    /**
     * Minimal percent-encoding for the query parameters (space → `%20`). The
     * selection is a single word or short phrase, so encoding accents and the
     * handful of reserved characters is sufficient; anything outside
     * `[A-Za-z0-9._~-]` is UTF-8 encoded byte by byte.
     */
    private fun encode(value: String): String =
        buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (c.isLetterOrDigit() && c.code < ASCII_MAX || c in "-._~") {
                    append(c)
                } else {
                    append('%')
                    append(HEX[(byte.toInt() shr 4) and 0x0F])
                    append(HEX[byte.toInt() and 0x0F])
                }
            }
        }

    private companion object {
        const val HEX = "0123456789ABCDEF"

        /** Highest code point of 7-bit ASCII; percent-encoding only escapes above it. */
        private const val ASCII_MAX = 128
    }
}

/**
 * Production transport over the injected Ktor client (OkHttp engine, matching
 * the catalog/update clients). Sends the identified UA so upstream traffic is
 * attributable. Network failures throw and are caught by the client.
 */
class KtorTranslationHttpTransport(
    private val client: HttpClient,
    private val userAgent: String = NEXO_TRANSLATION_USER_AGENT,
) : TranslationHttpTransport {
    override suspend fun get(url: String): TranslationHttpResponse {
        val response =
            client.get(url) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json")
            }
        return TranslationHttpResponse(response.status.value, response.bodyAsText())
    }
}
