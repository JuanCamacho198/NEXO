package com.nexo.data.remote.wiktionary

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wiktionary client's totality: a transport failure, a missing page, an HTTP
 * error and a malformed body each resolve to their OWN terminal value — a
 * failure never masquerades as a missing page (ODD invariant 2).
 */
class WiktionaryClientTest {
    private class FakeTransport(
        var response: WiktionaryHttpResponse? = null,
        var throwOnGet: Throwable? = null,
    ) : WiktionaryHttpTransport {
        var lastUrl: String? = null

        override suspend fun get(url: String): WiktionaryHttpResponse {
            lastUrl = url
            throwOnGet?.let { throw it }
            return response ?: WiktionaryHttpResponse(200, "")
        }
    }

    private fun ok(wikitext: String) = """{"parse":{"title":"house","pageid":1,"wikitext":{"*":${quote(wikitext)}}}}"""

    private fun quote(s: String): String = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    @Test
    fun `fetchWikitext returns Success for a real page`() =
        runTest {
            val transport = FakeTransport(response = WiktionaryHttpResponse(200, ok("== {{lengua|en}} ==")))
            val client = WiktionaryClient(transport)
            val result = client.fetchWikitext("house", "es")
            assertTrue(result is WiktionaryFetch.Success)
            assertEquals("== {{lengua|en}} ==", (result as WiktionaryFetch.Success).wikitext)
        }

    @Test
    fun `fetchWikitext builds the expected endpoint`() =
        runTest {
            val transport = FakeTransport(response = WiktionaryHttpResponse(200, ok("x")))
            val client = WiktionaryClient(transport)
            client.fetchWikitext("house", "es")
            val url = transport.lastUrl!!
            assertTrue(url.startsWith("https://es.wiktionary.org/w/api.php"))
            assertTrue(url.contains("action=parse"))
            assertTrue(url.contains("page=house"))
            assertTrue(url.contains("prop=wikitext"))
        }

    @Test
    fun `fetchWikitext percent-encodes a multi-word selection`() =
        runTest {
            val transport = FakeTransport(response = WiktionaryHttpResponse(200, ok("x")))
            val client = WiktionaryClient(transport)
            client.fetchWikitext("give up", "es")
            assertTrue(transport.lastUrl!!.contains("page=give%20up"))
        }

    /** A 200 with a top-level `error` is the API saying the page does not exist. */
    @Test
    fun `fetchWikitext returns MissingPage for an error envelope`() =
        runTest {
            val transport =
                FakeTransport(
                    response = WiktionaryHttpResponse(200, """{"error":{"code":"missingtitle"}}"""),
                )
            val client = WiktionaryClient(transport)
            assertEquals(WiktionaryFetch.MissingPage, client.fetchWikitext("zzzz", "es"))
        }

    @Test
    fun `fetchWikitext returns Network failure when the transport throws`() =
        runTest {
            val transport = FakeTransport(throwOnGet = java.io.IOException("no network"))
            val client = WiktionaryClient(transport)
            val result = client.fetchWikitext("house", "es")
            assertEquals(WiktionaryErrorCode.NETWORK, (result as WiktionaryFetch.Failure).code)
        }

    @Test
    fun `fetchWikitext returns HTTP failure on a non-2xx status`() =
        runTest {
            val transport = FakeTransport(response = WiktionaryHttpResponse(503, "busy"))
            val client = WiktionaryClient(transport)
            val result = client.fetchWikitext("house", "es")
            assertEquals(WiktionaryErrorCode.HTTP_ERROR, (result as WiktionaryFetch.Failure).code)
        }

    @Test
    fun `fetchWikitext returns Parse failure on malformed json`() =
        runTest {
            val transport = FakeTransport(response = WiktionaryHttpResponse(200, "{ not json"))
            val client = WiktionaryClient(transport)
            val result = client.fetchWikitext("house", "es")
            assertEquals(WiktionaryErrorCode.PARSE_ERROR, (result as WiktionaryFetch.Failure).code)
        }

    @Test
    fun `fetchWikitext rejects an empty word without hitting the network`() =
        runTest {
            val transport = FakeTransport()
            val client = WiktionaryClient(transport)
            val result = client.fetchWikitext("   ", "es")
            assertTrue(result is WiktionaryFetch.Failure)
            assertNull(transport.lastUrl)
        }

    /** A missing page is NOT a failure: the two must stay distinguishable. */
    @Test
    fun `missing page and transport failure are different values`() =
        runTest {
            val missing =
                WiktionaryClient(FakeTransport(response = WiktionaryHttpResponse(200, """{"error":{}}""")))
                    .fetchWikitext("zzzz", "es")
            val failed =
                WiktionaryClient(FakeTransport(throwOnGet = java.io.IOException("down")))
                    .fetchWikitext("zzzz", "es")
            assertEquals(WiktionaryFetch.MissingPage, missing)
            assertTrue(failed is WiktionaryFetch.Failure)
        }
}
