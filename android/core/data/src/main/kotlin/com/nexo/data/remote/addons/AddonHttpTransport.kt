package com.nexo.data.remote.addons

import com.nexo.data.remote.catalog.ANDROID_USER_AGENT
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders

/**
 * Thin addon fetch boundary reusing the shared catalog HttpClient (design A2):
 * no new client is constructed — DI injects the same catalogHttpClient instance.
 * Sends the catalog UA; no cookies; failure surfaces as ADDON_FETCH_NETWORK.
 * HTTP status and content-type pass through verbatim for ManifestValidator.
 */
interface AddonHttpTransport {
    suspend fun fetch(url: String): AddonResource
}

data class AddonResource(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
)

class KtorAddonHttpTransport(
    private val client: HttpClient,
    private val userAgent: String = ANDROID_USER_AGENT,
) : AddonHttpTransport {
    override suspend fun fetch(url: String): AddonResource =
        try {
            val response =
                client.get(url) {
                    header(HttpHeaders.UserAgent, userAgent)
                    header(HttpHeaders.Accept, "application/json")
                }
            AddonResource(
                status = response.status.value,
                contentType = response.headers[HttpHeaders.ContentType],
                body = response.bodyAsBytes(),
            )
        } catch (err: Throwable) {
            if (err is AddonFetchException) throw err
            throw AddonFetchException(AddonFetchErrorCode.NETWORK, "addon request failed")
        }
}

/** Fake for offline tests: returns canned results or throws the given error. */
class FakeAddonHttpTransport(
    private val result: AddonResource? = null,
    private val error: Exception? = null,
) : AddonHttpTransport {
    var calls = 0
        private set

    override suspend fun fetch(url: String): AddonResource {
        calls++
        val failure = error
        if (failure != null) {
            if (failure is AddonFetchException) throw failure
            throw AddonFetchException(AddonFetchErrorCode.NETWORK, failure.message ?: "transport failure")
        }
        return result ?: throw AddonFetchException(AddonFetchErrorCode.NETWORK, "no result configured")
    }
}
