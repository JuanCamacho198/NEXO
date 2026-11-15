package com.nexo.domain.model

import java.io.File

/**
 * Port that streams a catalog download URL into a local [File].
 *
 * Domain-owned so the download-and-import use case never names a transport
 * implementation. The data layer provides the Ktor-backed adapter; tests can
 * provide any fake.
 *
 * [onProgress] is invoked per received chunk with
 * `(bytesSoFar, totalBytesOrNull)`, where `totalBytes` is whatever the source
 * could advertise up front and is `null` when it did not.
 */
fun interface CatalogFileSource {
    suspend fun download(
        url: String,
        destination: File,
        onProgress: (Long, Long?) -> Unit,
    )
}
