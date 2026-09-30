package com.nexo.update

import com.nexo.data.update.UpdateFeedService
import com.nexo.domain.update.UpdateFeedFailure
import com.nexo.domain.update.UpdateFeedFetch
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException

private const val FEED_URL = "https://example.com/latest-android.json"

private const val VALID_FEED_BODY =
    "{\"version\":\"0.4.0\",\"versionCode\":400," +
        "\"notes\":\"Mock release notes.\",\"pubDate\":\"2026-09-28T12:00:00Z\"," +
        "\"channel\":\"stable\",\"assets\":[{\"url\":" +
        "\"https://github.com/nexo/nextpage/releases/download/v0.4.0/nextpage-android-v0.4.0.apk\"," +
        "\"abi\":\"universal\",\"size\":12345678}]}"

class UpdateFeedServiceTest {
    private fun service(
        handler: MockEngine,
        feedUrl: String = FEED_URL,
    ): UpdateFeedService {
        val client =
            HttpClient(handler) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        return UpdateFeedService(client, feedUrl)
    }

    private fun okEngine(body: String): MockEngine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }

    @Test
    fun validFeed_parsesCandidate(): Unit =
        runBlocking {
            val result = service(okEngine(VALID_FEED_BODY)).fetch()

            assertTrue(result is UpdateFeedFetch.Found)
            val found = result as UpdateFeedFetch.Found
            assertEquals("0.4.0", found.version)
            assertEquals(400, found.versionCode)
            assertEquals("stable", found.channel)
            assertTrue(found.assetUrl.endsWith("nextpage-android-v0.4.0.apk"))
        }

    @Test
    fun repoMockFixture_parsesWhenPresent(): Unit =
        runBlocking {
            val fixture =
                File(System.getProperty("user.dir"), "../../mocks/update-feed/android-latest.json")
            Assume.assumeTrue(fixture.exists())
            val result = service(okEngine(fixture.readText())).fetch()

            assertTrue(result is UpdateFeedFetch.Found)
            val found = result as UpdateFeedFetch.Found
            assertEquals("0.3.1", found.version)
            assertEquals(301, found.versionCode)
        }

    @Test
    fun missingVersionCode_isMalformed(): Unit =
        runBlocking {
            val body = "{\"version\":\"0.4.0\",\"notes\":\"x\",\"assets\":[{\"url\":\"https://example.com/a.apk\"}]}"
            val result = service(okEngine(body)).fetch()

            assertEquals(UpdateFeedFetch.Failed(UpdateFeedFailure.MALFORMED), result)
        }

    @Test
    fun invalidJson_isMalformed(): Unit =
        runBlocking {
            val result = service(okEngine("not json")).fetch()

            assertEquals(UpdateFeedFetch.Failed(UpdateFeedFailure.MALFORMED), result)
        }

    @Test
    fun emptyAssets_isMalformed(): Unit =
        runBlocking {
            val body = "{\"version\":\"0.4.0\",\"versionCode\":400,\"assets\":[]}"
            val result = service(okEngine(body)).fetch()

            assertEquals(UpdateFeedFetch.Failed(UpdateFeedFailure.MALFORMED), result)
        }

    @Test
    fun httpError_isUnreachable(): Unit =
        runBlocking {
            val engine = MockEngine { respondError(HttpStatusCode.NotFound) }
            val result = service(engine).fetch()

            assertEquals(UpdateFeedFetch.Failed(UpdateFeedFailure.UNREACHABLE), result)
        }

    @Test
    fun timeout_isUnreachable(): Unit =
        runBlocking {
            val engine = MockEngine { throw SocketTimeoutException("timeout") }
            val result = service(engine).fetch()

            assertEquals(UpdateFeedFetch.Failed(UpdateFeedFailure.UNREACHABLE), result)
        }

    @Test
    fun blankFeedUrl_isDisabled(): Unit =
        runBlocking {
            val result = service(okEngine(VALID_FEED_BODY), feedUrl = "  ").fetch()

            assertEquals(UpdateFeedFetch.Failed(UpdateFeedFailure.DISABLED), result)
        }
}
