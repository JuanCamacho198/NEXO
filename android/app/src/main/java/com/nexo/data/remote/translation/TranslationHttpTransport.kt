package com.nexo.data.remote.translation

/**
 * Raw HTTP boundary for the MyMemory translation API, so the client stays
 * fully testable offline with fakes (mirrors the catalog transport seam).
 */
interface TranslationHttpTransport {
    suspend fun get(url: String): TranslationHttpResponse
}

/** Raw upstream result: HTTP status + body. */
data class TranslationHttpResponse(
    val status: Int,
    val body: String,
)

/** Result of a translation attempt — never throws across the boundary. */
sealed interface TranslationResult {
    /** A genuinely new string (passed [com.nexo.domain.translation.TranslationPolicy.isRealTranslation]). */
    data class Success(
        val translated: String,
        val match: Double?,
    ) : TranslationResult

    /**
     * The upstream answered but did not actually translate (echo / empty body).
     * Surfaced so the UI shows a "no translation available" state instead of a
     * misleading identical string.
     */
    data object NotTranslated : TranslationResult

    /** No network, timeout, or the transport threw. */
    data object NetworkError : TranslationResult

    /** HTTP error, `responseStatus != 200`, parse failure, or missing payload. */
    data class Failure(
        val code: TranslationErrorCode,
        val message: String,
    ) : TranslationResult
}

enum class TranslationErrorCode {
    HTTP_ERROR,
    UPSTREAM_STATUS,
    PARSE_ERROR,
    EMPTY_RESPONSE,
    NO_LANGPAIR,
}

/**
 * Absolute base URL of the MyMemory public endpoint. HTTPS-only by constant,
 * consistent with the other public-network egress in the app.
 */
const val MYMEMORY_BASE_URL = "https://api.mymemory.translated.net/get"

/** Identified UA so MyMemory can attribute traffic (mirrors the catalog UA policy). */
const val NEXO_TRANSLATION_USER_AGENT = "Nexo/Android (translation)"
