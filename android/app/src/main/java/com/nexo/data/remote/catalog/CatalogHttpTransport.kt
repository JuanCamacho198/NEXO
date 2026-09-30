package com.nexo.data.remote.catalog

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay

/** Raw upstream result shared by both catalog datasources. */
data class CatalogHttpResponse(
    val status: Int,
    val body: String,
)

/** Narrow HTTP boundary so datasources stay fully testable offline with fakes. */
interface CatalogHttpTransport {
    suspend fun get(url: String): CatalogHttpResponse
}

/** Search payload shared by both datasources (books + authority total). */
data class CatalogSearchResult(
    val books: List<CatalogBook>,
    val totalCount: Int,
)

/**
 * Production transport over the injected Ktor client.
 * Sends the identified catalog UA + JSON accept on every request.
 * Network failures surface as NETWORK_ERROR; HTTP codes pass through
 * so callers can apply the single-delayed-retry policy.
 */
class KtorCatalogHttpTransport(
    private val client: HttpClient,
    private val userAgent: String = ANDROID_USER_AGENT,
) : CatalogHttpTransport {
    override suspend fun get(url: String): CatalogHttpResponse {
        try {
            val response =
                client.get(url) {
                    header(HttpHeaders.UserAgent, userAgent)
                    header(HttpHeaders.Accept, "application/json")
                }
            return CatalogHttpResponse(response.status.value, response.bodyAsText())
        } catch (err: Throwable) {
            if (isCatalogError(err)) throw err
            throw catalogError(CatalogErrorCode.NETWORK_ERROR, "catalog request failed")
        }
    }
}

/**
 * GET with exactly one delayed retry on 429/5xx (mirrors `fetchWithRetry`).
 * Network failures surface as NETWORK_ERROR; HTTP failures as mapped codes.
 */
suspend fun CatalogHttpTransport.getWithRetry(url: String): CatalogHttpResponse {
    val first = get(url)
    if (first.status.isSuccessLike()) return first
    if (!shouldRetryStatus(first.status)) {
        throw catalogError(mapHttpStatusToCode(first.status), "upstream status ${first.status}")
    }
    delay(backoffDelayMs(0))
    val second = get(url)
    if (!second.status.isSuccessLike()) {
        throw catalogError(mapHttpStatusToCode(second.status), "upstream status ${second.status}")
    }
    return second
}

private fun Int.isSuccessLike(): Boolean = isHttpSuccess()
