package com.nexo.data.remote.translation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MyMemory `get` response envelope.
 *
 * Only the fields the client needs are mapped; everything else is ignored so
 * upstream additions never break parsing. `match` is captured for diagnostics
 * but is explicitly NOT trusted as a translation-quality signal (see
 * [com.nexo.domain.translation.TranslationPolicy.isRealTranslation]).
 */
@Serializable
internal data class MyMemoryResponse(
    @SerialName("responseData")
    val responseData: ResponseData? = null,
    @SerialName("responseStatus")
    val responseStatus: Int? = null,
    @SerialName("responseDetails")
    val responseDetails: String? = null,
)

@Serializable
internal data class ResponseData(
    @SerialName("translatedText")
    val translatedText: String? = null,
    @SerialName("match")
    val match: Double? = null,
    @SerialName("quotaFinished")
    val quotaFinished: Boolean? = null,
)

/**
 * Parses a raw MyMemory HTTP response into a [TranslationResult].
 *
 * Pure and total: every failure mode (non-200 HTTP, `responseStatus != 200`,
 * unparseable body, empty payload, echo) maps to a distinct terminal value —
 * it never throws. [original] and [langPair] are needed to decide whether the
 * payload is a real translation via [TranslationPolicy].
 */
internal fun parseMyMemoryResponse(
    httpStatus: Int,
    body: String,
    original: String,
    json: Json,
): TranslationResult {
    if (httpStatus !in 200..299) {
        return TranslationResult.Failure(
            TranslationErrorCode.HTTP_ERROR,
            "HTTP $httpStatus",
        )
    }
    if (body.isBlank()) {
        return TranslationResult.Failure(TranslationErrorCode.EMPTY_RESPONSE, "empty body")
    }
    val parsed =
        try {
            json.decodeFromString(MyMemoryResponse.serializer(), body)
        } catch (e: Exception) {
            return TranslationResult.Failure(
                TranslationErrorCode.PARSE_ERROR,
                e.message ?: "malformed response",
            )
        }
    val status = parsed.responseStatus
    if (status != null && status != 200) {
        return TranslationResult.Failure(
            TranslationErrorCode.UPSTREAM_STATUS,
            parsed.responseDetails ?: "responseStatus $status",
        )
    }
    val translated = parsed.responseData?.translatedText
    if (translated.isNullOrBlank()) {
        return TranslationResult.Failure(TranslationErrorCode.EMPTY_RESPONSE, "no translatedText")
    }
    if (!com.nexo.domain.translation.TranslationPolicy
            .isRealTranslation(original, translated)
    ) {
        return TranslationResult.NotTranslated
    }
    return TranslationResult.Success(translated = translated, match = parsed.responseData.match)
}
