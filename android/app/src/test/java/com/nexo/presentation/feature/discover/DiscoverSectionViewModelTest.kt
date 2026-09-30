package com.nexo.presentation.feature.discover

import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.presentation.viewmodel.DiscoverStatus
import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Section-list paging: exactly one selector is honoured, and the list stops
 * fetching once the provider reports `nextPage == null`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverSectionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeSectionProvider(
        private val firstPage: PagedResult = PagedResult(emptyList(), null, 0),
        private val secondPage: PagedResult = PagedResult(emptyList(), null, 0),
    ) : CatalogProvider {
        val featuredCalls = mutableListOf<Pair<CatalogFeaturedSort, Int>>()
        val searchSourceCalls = mutableListOf<Triple<String, String, Int>>()

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult = PagedResult(emptyList(), null, 0)

        override suspend fun getDetails(id: String): CatalogBook = throw CatalogException(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = "n/a"

        override fun listSources(): List<CatalogSourceInfo> = emptyList()

        override suspend fun featured(
            sort: CatalogFeaturedSort,
            page: Int,
        ): PagedResult {
            featuredCalls.add(sort to page)
            return if (page == 1) firstPage else secondPage
        }

        override fun supportsFeatured(): Boolean = true

        override suspend fun searchSource(
            sourceId: String,
            query: String,
            page: Int,
        ): PagedResult {
            searchSourceCalls.add(Triple(sourceId, query, page))
            return if (page == 1) firstPage else secondPage
        }
    }

    private fun book(
        id: String,
        title: String,
    ) = CatalogBook(
        id = id,
        provider = "builtin:gutendex",
        title = title,
        authors = listOf("Author"),
        coverUrl = null,
        languages = listOf("en"),
        subjects = emptyList(),
        downloadUrl = null,
    )

    @Test
    fun featuredSectionPagesUntilExhaustedThenStopsFetching() =
        runTest {
            val provider =
                FakeSectionProvider(
                    firstPage =
                        PagedResult(
                            listOf(book("gutendex:1", "One"), book("gutendex:2", "Two")),
                            nextPage = 2,
                            totalCount = 3,
                        ),
                    secondPage = PagedResult(listOf(book("gutendex:3", "Three")), nextPage = null, totalCount = 3),
                )
            val vm =
                DiscoverSectionViewModel(
                    catalogProvider = provider,
                    sectionTitle = "Recién agregados al catálogo",
                    sort = CatalogFeaturedSort.NEWEST,
                    sourceId = null,
                )

            assertEquals(DiscoverStatus.LOADED, vm.uiState.value.status)
            assertEquals(2, vm.uiState.value.books.size)
            assertEquals(2, vm.uiState.value.nextPage)

            vm.loadNextPage()

            assertEquals(3, vm.uiState.value.books.size)
            assertNull(vm.uiState.value.nextPage)
            assertEquals(2, provider.featuredCalls.size)

            // Exhausted list: no further fetch may be scheduled.
            vm.loadNextPage()
            vm.loadNextPage()
            assertEquals(2, provider.featuredCalls.size)
        }

    @Test
    fun sourceSelectorRoutesToSearchSource() =
        runTest {
            val provider =
                FakeSectionProvider(
                    firstPage = PagedResult(listOf(book("gutendex:1", "One")), null, 1),
                )
            val vm =
                DiscoverSectionViewModel(
                    catalogProvider = provider,
                    sectionTitle = "Gutendex",
                    sort = null,
                    sourceId = "builtin:gutendex",
                    term = "pride",
                )

            assertEquals(DiscoverStatus.LOADED, vm.uiState.value.status)
            assertEquals(listOf(Triple("builtin:gutendex", "pride", 1)), provider.searchSourceCalls)
            assertTrue(provider.featuredCalls.isEmpty())
        }

    @Test
    fun missingOrAmbiguousSelectorFailsClosedWithoutFetching() =
        runTest {
            val provider = FakeSectionProvider()

            val neither = DiscoverSectionViewModel(provider, "Empty", sort = null, sourceId = null)
            assertEquals(DiscoverStatus.EMPTY, neither.uiState.value.status)
            assertNull(neither.uiState.value.nextPage)

            val both =
                DiscoverSectionViewModel(
                    provider,
                    "Ambiguous",
                    sort = CatalogFeaturedSort.POPULAR,
                    sourceId = "builtin:gutendex",
                )
            assertEquals(DiscoverStatus.EMPTY, both.uiState.value.status)

            assertTrue(provider.featuredCalls.isEmpty())
            assertTrue(provider.searchSourceCalls.isEmpty())
        }

    @Test
    fun emptyFirstPageIsEmptyAndExhausted() =
        runTest {
            val provider = FakeSectionProvider(firstPage = PagedResult(emptyList(), null, 0))
            val vm =
                DiscoverSectionViewModel(
                    provider,
                    "Recién agregados al catálogo",
                    sort = CatalogFeaturedSort.NEWEST,
                    sourceId = null,
                )
            assertEquals(DiscoverStatus.EMPTY, vm.uiState.value.status)
            assertNull(vm.uiState.value.nextPage)
            vm.loadNextPage()
            assertEquals(1, provider.featuredCalls.size)
        }
}
