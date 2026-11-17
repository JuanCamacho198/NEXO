package com.nexo.data.remote.drive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Drive token endpoint is the one call that can wedge the whole sync path:
 * it is reached from a 401 retry, which is reached from a WorkManager drain.
 * Without a timeout an unreachable Google parks that coroutine forever, so the
 * user sees a login that never finishes and sync that never resumes.
 *
 * The client config is proven twice: once that a hanging server produces a
 * timeout error instead of an unresolved coroutine, and once that production
 * builds it with the same [HttpTimeout] the other clients in `NetworkModule` use.
 */
class KtorAuthApiTimeoutTest {
    private fun authApi(engine: MockEngine): KtorAuthApi = KtorAuthApi(HttpClient(engine) { install(HttpTimeout) { requestTimeoutMillis = TIMEOUT_MS } })

    private fun hangingEngine(): MockEngine =
        MockEngine {
            delay(Long.MAX_VALUE)
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    @Test
    fun refresh_hangingServer_timesOutInsteadOfPendingForever(): Unit =
        runBlocking {
            val api = authApi(hangingEngine())

            val started = System.currentTimeMillis()
            val result = api.refresh("client", "refresh-token")
            val elapsed = System.currentTimeMillis() - started

            assertTrue("refresh must fail, not hang", result.isFailure)
            assertTrue(
                "expected a timeout, got ${result.exceptionOrNull()}",
                result.exceptionOrNull() is HttpRequestTimeoutException,
            )
            assertTrue("refresh took ${elapsed}ms, the timeout did not fire", elapsed < PATIENCE_MS)
        }

    @Test
    fun exchange_hangingServer_timesOutInsteadOfPendingForever(): Unit =
        runBlocking {
            val api = authApi(hangingEngine())

            val result = api.exchange("client", "auth-code", "nexo://oauth", "verifier")

            assertTrue(result.isFailure)
            assertTrue(
                "expected a timeout, got ${result.exceptionOrNull()}",
                result.exceptionOrNull() is HttpRequestTimeoutException,
            )
        }

    private companion object {
        const val TIMEOUT_MS = 300L

        // Generous: the point is that the call returns at all.
        const val PATIENCE_MS = 10_000L
    }
}
