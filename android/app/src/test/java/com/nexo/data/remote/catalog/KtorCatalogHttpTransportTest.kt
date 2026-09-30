package com.nexo.data.remote.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Ktor wiring check for [KtorCatalogHttpTransport] against a MockEngine:
 * proves the identified UA + JSON accept headers actually go out and
 * that engine I/O failures surface NETWORK_ERROR. Production wires the
 * CIO engine over the same transport.
 */
class KtorCatalogHttpTransportTest {
    @Test fun get_sendsIdentifiedUaAndParsesBody() =
        runTest {
            var lastUserAgent: String? = null
            var lastAccept: String? = null
            val engine =
                MockEngine { request ->
                    lastUserAgent = request.headers[HttpHeaders.UserAgent]
                    lastAccept = request.headers[HttpHeaders.Accept]
                    respond(
                        ByteReadChannel("""{"count":1,"results":[]}"""),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val transport = KtorCatalogHttpTransport(HttpClient(engine))
            val res = transport.get("https://gutendex.com/books/?search=x")
            assertEquals(200, res.status)
            assertTrue(res.body.contains("\"count\":1"))
            assertEquals(ANDROID_USER_AGENT, lastUserAgent)
            assertEquals("application/json", lastAccept)
        }

    @Test fun get_mapsEngineFailureToNetworkError() =
        runTest {
            val engine = MockEngine { throw IOException("connection reset") }
            val transport = KtorCatalogHttpTransport(HttpClient(engine))
            try {
                transport.get("https://gutendex.com/books/")
                error("expected CatalogException")
            } catch (err: CatalogException) {
                assertEquals(CatalogErrorCode.NETWORK_ERROR, err.code)
            }
        }
}
