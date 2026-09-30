package com.nexo.data.remote.catalog

/** In-memory transport for offline datasource tests; records requested URLs. */
class FakeCatalogHttpTransport(
    private val handler: (url: String) -> CatalogHttpResponse,
    val requestedUrls: MutableList<String> = mutableListOf(),
) : CatalogHttpTransport {
    override suspend fun get(url: String): CatalogHttpResponse {
        requestedUrls.add(url)
        return handler(url)
    }
}
