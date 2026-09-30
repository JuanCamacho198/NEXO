package com.nexo.data.remote.catalog

import java.io.File

/**
 * Streams a catalog download URL into a local [File].
 *
 * Separate from [CatalogHttpTransport] on purpose: that port is JSON-GET
 * shaped (returns a decoded body string) and must not grow a binary/streaming
 * surface. Catalog downloads reuse the same identified client but a distinct
 * port so JSON and binary traffic stay independently testable.
 */
interface CatalogFileDownloader {
    /**
     * Streams [url] into [destination], invoking [onProgress] per received
     * chunk with `(bytesSoFar, totalBytesOrNull)`.
     *
     * [totalBytes] is taken from the GET response `Content-Length` — no extra
     * HEAD request is issued and it is `null` for chunked responses.
     *
     * Failure surfaces as [CatalogException] with
     * [CatalogErrorCode.NETWORK_ERROR] (transport) or
     * [CatalogErrorCode.UPSTREAM_ERROR] (non-2xx HTTP status).
     */
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    )
}
