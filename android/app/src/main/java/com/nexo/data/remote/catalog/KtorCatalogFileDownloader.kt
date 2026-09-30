package com.nexo.data.remote.catalog

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Production [CatalogFileDownloader] over the shared catalog [HttpClient].
 *
 * Reads the response body in fixed-size chunks straight into the destination
 * file so a large EPUB never has to fit in memory, reporting
 * `(bytesSoFar, contentLengthOrNull)` after every chunk. `totalBytes` comes
 * from the GET `Content-Length` header — no extra HEAD request.
 *
 * Not part of [CatalogHttpTransport]: catalog JSON GETs stay on that narrow
 * port, binary streaming stays here. Both share the same client identity
 * (identified UA + timeouts from `NetworkModule.catalogHttpClient`).
 */
class KtorCatalogFileDownloader(
    private val client: HttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CatalogFileDownloader {
    override suspend fun download(
        url: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    ): Unit =
        withContext(ioDispatcher) {
            try {
                destination.parentFile?.mkdirs()
                client.prepareGet(url).execute { response ->
                    if (!response.status.isSuccess()) {
                        throw catalogError(
                            mapHttpStatusToCode(response.status.value),
                            "download status ${response.status.value}",
                        )
                    }
                    val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    val channel = response.bodyAsChannel()
                    destination.outputStream().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = channel.readAvailable(buffer, 0, buffer.size)
                            if (read == -1) break
                            if (read > 0) {
                                out.write(buffer, 0, read)
                                written += read
                                onProgress(written, totalBytes)
                            }
                        }
                        out.flush()
                    }
                }
            } catch (err: Throwable) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                if (isCatalogError(err)) throw err
                throw catalogError(CatalogErrorCode.NETWORK_ERROR, "catalog download failed")
            }
        }
}
