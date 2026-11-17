package com.nexo.di.modules

import com.nexo.data.remote.drive.DriveTokenApi
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auth client is built inside `NetworkModule.provideDriveTokenApi()`, so the
 * only way to prove it carries a timeout is to drive the production provider
 * with a hanging engine.
 *
 * A hanging Google endpoint must fail the refresh, not park the caller forever:
 * the refresh sits under a 401 retry, which sits under a WorkManager drain, so
 * an unresolved call strands both login and sync.
 */
class AuthClientTimeoutTest {
    private fun hangingEngine(): MockEngine =
        MockEngine {
            delay(Long.MAX_VALUE)
            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

    @Test
    fun driveTokenRefresh_hangingServer_timesOutInsteadOfPendingForever() {
        val api: DriveTokenApi = NetworkModule.provideDriveTokenApiWith(hangingEngine())

        val started = System.currentTimeMillis()
        val result = runBlocking { api.refresh("client", "refresh-token") }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("refresh must fail, not hang", result.isFailure)
        assertEquals(
            HttpRequestTimeoutException::class.java,
            result.exceptionOrNull()?.let { unwrapTimeout(it) },
        )
        assertTrue("refresh took ${elapsed}ms, no client timeout fired", elapsed < PATIENCE_MS)
    }

    /** Ktor may wrap the engine failure; the cause chain carries the timeout. */
    private fun unwrapTimeout(error: Throwable): Class<*>? {
        var current: Throwable? = error
        while (current != null) {
            if (current is HttpRequestTimeoutException) return HttpRequestTimeoutException::class.java
            current = current.cause
        }
        return error::class.java
    }

    private companion object {
        // Generous: the point is that the call returns at all.
        const val PATIENCE_MS = 60_000L
    }
}
