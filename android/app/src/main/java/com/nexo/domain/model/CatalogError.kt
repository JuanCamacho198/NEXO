package com.nexo.domain.model

/** 404 — the requested catalog resource does not exist. */
const val HTTP_NOT_FOUND = 404

/** 429 — the upstream source asked the client to slow down. */
const val HTTP_TOO_MANY_REQUESTS = 429

/** First 5xx status. */
const val HTTP_SERVER_ERROR_MIN = 500

/** Last 5xx status. */
const val HTTP_SERVER_ERROR_MAX = 599

/**
 * Stable catalog error codes shared by desktop and Android.
 * Messages are redacted at the boundary; only [code] is contractual.
 */
enum class CatalogErrorCode {
    INVALID_PAGE,
    UNAVAILABLE_DOWNLOAD,
    NOT_FOUND,
    RATE_LIMITED,
    UPSTREAM_ERROR,
    NETWORK_ERROR,
}

class CatalogException(
    val code: CatalogErrorCode,
    message: String,
    val retryable: Boolean =
        code == CatalogErrorCode.RATE_LIMITED ||
            code == CatalogErrorCode.NETWORK_ERROR,
) : Exception("$code: $message")

/** Build a typed catalog error without leaking upstream details. */
fun catalogError(
    code: CatalogErrorCode,
    detail: String? = null,
): CatalogException = CatalogException(code, detail ?: code.name)

fun isCatalogError(err: Throwable): Boolean = err is CatalogException

/** Map an upstream HTTP status to a stable contract code. */
fun mapHttpStatusToCode(status: Int): CatalogErrorCode =
    when (status) {
        HTTP_NOT_FOUND -> CatalogErrorCode.NOT_FOUND
        HTTP_TOO_MANY_REQUESTS -> CatalogErrorCode.RATE_LIMITED
        in HTTP_SERVER_ERROR_MIN..HTTP_SERVER_ERROR_MAX -> CatalogErrorCode.UPSTREAM_ERROR
        else -> CatalogErrorCode.UPSTREAM_ERROR
    }
