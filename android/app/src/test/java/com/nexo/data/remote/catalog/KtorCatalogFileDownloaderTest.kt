package com.nexo.data.remote.catalog

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contract evidence for [KtorCatalogFileDownloader]: body is streamed to disk,
 * `Content-Length` surfaces as `totalBytes`, and failures are typed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KtorCatalogFileDownloaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun download_streamsBody_andReportsContentLength() =
        runTest {
            val payload = ByteArray(2048) { 7 }
            val engine =
                MockEngine {
                    respond(
                        content = payload,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentLength, payload.size.toString()),
                    )
                }
            val client = HttpClient(engine)
            val destination = tempFolder.newFile("book.epub")
            var lastBytes = 0L
            var lastTotal: Long? = null

            KtorCatalogFileDownloader(client, UnconfinedTestDispatcher(testScheduler))
                .download("https://example.com/book.epub", destination) { bytes, total ->
                    lastBytes = bytes
                    lastTotal = total
                }

            assertEquals(payload.size.toLong(), destination.length())
            assertEquals(payload.size.toLong(), lastBytes)
            assertEquals(payload.size.toLong(), lastTotal)
        }

    @Test
    fun nonSuccessStatus_mapsToUpstreamError() =
        runTest {
            val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
            val client = HttpClient(engine)
            val destination = tempFolder.newFile("book.epub")

            val error =
                runCatching {
                    KtorCatalogFileDownloader(client, UnconfinedTestDispatcher(testScheduler))
                        .download("https://example.com/book.epub", destination) { _, _ -> }
                }.exceptionOrNull()

            assertTrue(error is CatalogException)
            assertEquals(CatalogErrorCode.UPSTREAM_ERROR, (error as CatalogException).code)
        }

    @Test
    fun engineFailure_mapsToNetworkError() =
        runTest {
            val engine = MockEngine { throw java.io.IOException("boom") }
            val client = HttpClient(engine)
            val destination = tempFolder.newFile("book.epub")

            val error =
                runCatching {
                    KtorCatalogFileDownloader(client, UnconfinedTestDispatcher(testScheduler))
                        .download("https://example.com/book.epub", destination) { _, _ -> }
                }.exceptionOrNull()

            assertTrue(error is CatalogException)
            assertEquals(CatalogErrorCode.NETWORK_ERROR, (error as CatalogException).code)
        }
}
