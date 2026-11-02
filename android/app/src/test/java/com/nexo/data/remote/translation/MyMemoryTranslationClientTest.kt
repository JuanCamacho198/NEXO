package com.nexo.data.remote.translation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyMemoryTranslationClientTest {
    private class FakeTransport(
        var response: TranslationHttpResponse? = null,
        var throwOnGet: Throwable? = null,
    ) : TranslationHttpTransport {
        var lastUrl: String? = null

        override suspend fun get(url: String): TranslationHttpResponse {
            lastUrl = url
            throwOnGet?.let { throw it }
            return response ?: TranslationHttpResponse(200, "")
        }
    }

    private fun ok(body: String) =
        """
        {"responseData":{"translatedText":"$body","match":0.99,"quotaFinished":false},"responseStatus":200}
        """.trimIndent()

    @Test
    fun `translate returns Success for a real translation`() =
        runTest {
            val transport = FakeTransport(response = TranslationHttpResponse(200, ok("French")))
            val client = MyMemoryTranslationClient(transport)
            val result = client.translate("francés", "es", "en")
            assertTrue(result is TranslationResult.Success)
            assertEquals("French", (result as TranslationResult.Success).translated)
        }

    @Test
    fun `translate builds the expected langpair url`() =
        runTest {
            val transport = FakeTransport(response = TranslationHttpResponse(200, ok("hello")))
            val client = MyMemoryTranslationClient(transport)
            client.translate("hola", "es-ES", "en-US")
            assertTrue(transport.lastUrl!!.contains("langpair=es%7Cen"))
            assertTrue(transport.lastUrl!!.contains("q=hola"))
        }

    @Test
    fun `translate returns NotTranslated when the API echoes the source`() =
        runTest {
            // The verified `francés` → `francés` with match 0.99 defect.
            val transport = FakeTransport(response = TranslationHttpResponse(200, ok("francés")))
            val client = MyMemoryTranslationClient(transport)
            assertEquals(TranslationResult.NotTranslated, client.translate("francés", "es", "en"))
        }

    @Test
    fun `translate returns NetworkError when the transport throws`() =
        runTest {
            val transport = FakeTransport(throwOnGet = java.io.IOException("no network"))
            val client = MyMemoryTranslationClient(transport)
            assertEquals(TranslationResult.NetworkError, client.translate("hola", "es", "en"))
        }

    @Test
    fun `translate returns HTTP_ERROR on a non-2xx status`() =
        runTest {
            val transport = FakeTransport(response = TranslationHttpResponse(503, "busy"))
            val client = MyMemoryTranslationClient(transport)
            val result = client.translate("hola", "es", "en")
            assertTrue(result is TranslationResult.Failure)
            assertEquals(TranslationErrorCode.HTTP_ERROR, (result as TranslationResult.Failure).code)
        }

    @Test
    fun `translate returns UPSTREAM_STATUS when responseStatus is not 200`() =
        runTest {
            val body = """{"responseData":{"translatedText":"hello"},"responseStatus":403,"responseDetails":"quota"}"""
            val transport = FakeTransport(response = TranslationHttpResponse(200, body))
            val client = MyMemoryTranslationClient(transport)
            val result = client.translate("hola", "es", "en")
            assertEquals(TranslationErrorCode.UPSTREAM_STATUS, (result as TranslationResult.Failure).code)
        }

    @Test
    fun `translate returns PARSE_ERROR on malformed json`() =
        runTest {
            val transport = FakeTransport(response = TranslationHttpResponse(200, "{ not json"))
            val client = MyMemoryTranslationClient(transport)
            val result = client.translate("hola", "es", "en")
            assertEquals(TranslationErrorCode.PARSE_ERROR, (result as TranslationResult.Failure).code)
        }

    @Test
    fun `translate returns EMPTY_RESPONSE on a blank body`() =
        runTest {
            val transport = FakeTransport(response = TranslationHttpResponse(200, ""))
            val client = MyMemoryTranslationClient(transport)
            val result = client.translate("hola", "es", "en")
            assertEquals(TranslationErrorCode.EMPTY_RESPONSE, (result as TranslationResult.Failure).code)
        }

    @Test
    fun `translate rejects an empty query without hitting the network`() =
        runTest {
            val transport = FakeTransport()
            val client = MyMemoryTranslationClient(transport)
            val result = client.translate("   ", "es", "en")
            assertEquals(TranslationErrorCode.EMPTY_RESPONSE, (result as TranslationResult.Failure).code)
            assertEquals(null, transport.lastUrl)
        }

    @Test
    fun `translate rejects identical source and target`() =
        runTest {
            val transport = FakeTransport()
            val client = MyMemoryTranslationClient(transport)
            val result = client.translate("hola", "es-ES", "es-419")
            assertEquals(TranslationErrorCode.NO_LANGPAIR, (result as TranslationResult.Failure).code)
            assertEquals(null, transport.lastUrl)
        }
}
