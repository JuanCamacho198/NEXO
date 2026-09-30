package com.nexo.data.remote.catalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** U1: all providers failing still yields an empty result with no crash. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompositeAllFailTest {
    private fun failing(sourceId: String): CatalogProvider =
        object : CatalogProvider {
            override suspend fun search(
                query: String,
                page: Int,
            ): PagedResult = throw catalogError(CatalogErrorCode.UPSTREAM_ERROR, "boom")

            override suspend fun getDetails(id: String): CatalogBook = throw catalogError(CatalogErrorCode.NOT_FOUND, "n/a")

            override fun resolveDownloadUrl(
                formats: Map<String, String>,
                preferEpub: Boolean,
            ): String = throw catalogError(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "n/a")

            override fun listSources(): List<CatalogSourceInfo> = listOf(CatalogSourceInfo(sourceId, "Failing", CatalogSourceKind.BUILTIN))
        }

    @Test fun search_returnsEmptyPageWhenAllProvidersFail() =
        runTest {
            val catalog =
                CompositeCatalogProvider(
                    listOf(failing("builtin:failing-a"), failing("builtin:failing-b")),
                )
            // Must not throw: fail-closed empty page.
            val page = catalog.search("alice", 1)
            assertEquals(0, page.results.size)
            assertEquals(0, page.totalCount)
            assertNull(page.nextPage)
        }
}
