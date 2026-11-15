package com.nexo.data.remote.catalog

import com.nexo.domain.model.CatalogErrorCode
import com.nexo.domain.model.CatalogException
import com.nexo.domain.model.CatalogFileSource
import java.io.File

/**
 * Streams a catalog download URL into a local [File].
 *
 * The port itself is [CatalogFileSource], owned by `domain`, so the
 * download-and-import use case never names a transport implementation. This
 * alias is kept for the data layer's own bindings.
 *
 * Separate from [CatalogHttpTransport] on purpose: that port is JSON-GET
 * shaped (returns a decoded body string) and must not grow a binary/streaming
 * surface. Catalog downloads reuse the same identified client but a distinct
 * port so JSON and binary traffic stay independently testable.
 *
 * Failure surfaces as [CatalogException] with
 * [CatalogErrorCode.NETWORK_ERROR] (transport) or
 * [CatalogErrorCode.UPSTREAM_ERROR] (non-2xx HTTP status).
 */
typealias CatalogFileDownloader = CatalogFileSource
