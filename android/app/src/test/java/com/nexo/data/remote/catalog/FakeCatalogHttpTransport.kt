package com.nexo.data.remote.catalog

/** In-memory transport for offline datasource tests; records requested URLs. */
class FakeCatalogHttpTransport(
    private val handler: (url: String) -> CatalogHttpResponse,
    val requestedUrls: MutableList<String> = mutableListOf(),
    val requestedHeaders: MutableList<Map<String, String>> = mutableListOf(),
) : CatalogHttpTransport {
    override suspend fun get(
        url: String,
        extraHeaders: Map<String, String>,
    ): CatalogHttpResponse {
        requestedUrls.add(url)
        requestedHeaders.add(extraHeaders)
        return handler(url)
    }
}
