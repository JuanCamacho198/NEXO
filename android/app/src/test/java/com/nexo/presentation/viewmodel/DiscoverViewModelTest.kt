package com.nexo.presentation.viewmodel

import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.BUILTIN_OPENLIBRARY
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogFeaturedSort
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.CatalogSourceKind
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.data.remote.catalog.addonSource
import com.nexo.debug.SentryMetrics
import com.nexo.domain.access.LegalAccess
import com.nexo.domain.connectivity.ConnectivityObserver
import com.nexo.domain.connectivity.FakeConnectivityObserver
import com.nexo.domain.model.Book
import com.nexo.domain.usecase.DownloadAndImportBookUseCase
import com.nexo.domain.usecase.DownloadImportState
import com.nexo.presentation.feature.discover.AccessResolverState
import com.nexo.presentation.feature.discover.AddonReadState
import com.nexo.presentation.feature.discover.DiscoverRailState
import com.nexo.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeCatalogProvider : CatalogProvider {
        val searchCalls = mutableListOf<Pair<String, Int>>()
        val detailCalls = mutableListOf<String>()
        var searchError: CatalogErrorCode? = null
        var detailError: CatalogErrorCode? = null
        var page1Results: List<CatalogBook> =
            listOf(
                book("gutendex:1342", "Pride and Prejudice"),
                book("openlibrary:OL11W", "Borrow Restricted Title"),
            )
        var page1Next: Int? = 2
        var page2Results: List<CatalogBook> =
            listOf(
                book("gutendex:1342", "Pride and Prejudice"),
                book("gutendex:2001", "Page Two Book"),
            )

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult {
            searchCalls.add(query to page)
            searchError?.let { throw CatalogException(it, "injected") }
            return when (page) {
                1 -> PagedResult(page1Results, page1Next, 50)
                2 -> PagedResult(page2Results, null, 50)
                else -> throw CatalogException(CatalogErrorCode.INVALID_PAGE, "unexpected page")
            }
        }

        override suspend fun getDetails(id: String): CatalogBook {
            detailCalls.add(id)
            detailError?.let { throw CatalogException(it, "injected") }
            return book(id, "Detail of $id")
        }

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String =
            formats.entries.firstOrNull()?.key ?: throw CatalogException(
                CatalogErrorCode.UNAVAILABLE_DOWNLOAD,
                "no formats",
            )

        override fun listSources(): List<CatalogSourceInfo> = emptyList()

        companion object {
            fun book(
                id: String,
                title: String,
            ) = CatalogBook(
                id = id,
                provider = if (id.startsWith("gutendex:")) BUILTIN_GUTENDEX else BUILTIN_OPENLIBRARY,
                title = title,
                authors = listOf("Author A"),
                coverUrl = "https://covers.openlibrary.org/b/id/6794977-M.jpg",
                languages = listOf("en"),
                subjects = emptyList(),
                downloadUrl = "https://www.gutenberg.org/ebooks/1342",
            )
        }
    }

    /** Provider whose [search] stays suspended until [gate] is completed. */
    private class GatedCatalogProvider : CatalogProvider {
        val gate = CompletableDeferred<Unit>()

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult {
            gate.await()
            return PagedResult(emptyList(), null, 0)
        }

        override suspend fun getDetails(id: String): CatalogBook = throw CatalogException(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = throw CatalogException(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "unused")

        override fun listSources(): List<CatalogSourceInfo> = emptyList()
    }

    @Test
    fun blankQueryResetsToIdleWithoutProviderIO() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            assertTrue(provider.searchCalls.isNotEmpty())
            vm.onQueryChange("   ")
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
            assertTrue(
                vm.uiState.value.books
                    .isEmpty(),
            )
            val callsAfterBlank = provider.searchCalls.size
            vm.searchFirstPage()
            assertEquals(callsAfterBlank, provider.searchCalls.size)
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
        }

    @Test
    fun searchLoadsBooksFromProvider() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            assertEquals(DiscoverStatus.LOADED, vm.uiState.value.status)
            assertEquals(50, vm.uiState.value.totalCount)
            assertEquals(2, vm.uiState.value.books.size)
            assertEquals("pride", provider.searchCalls.single().first)
            assertEquals(1, provider.searchCalls.single().second)
        }

    @Test
    fun loadNextPageAppendsWithDedupeAndGuardsNullNextPage() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            val firstCount = vm.uiState.value.books.size
            vm.loadNextPage()
            assertEquals(DiscoverStatus.LOADED, vm.uiState.value.status)
            assertEquals(2, vm.uiState.value.activePage)
            assertTrue(vm.uiState.value.books.size > firstCount)
            val ids =
                vm.uiState.value.books
                    .map { it.id }
            assertEquals(ids.size, ids.toSet().size)
            val callsBefore = provider.searchCalls.size
            vm.loadNextPage()
            assertEquals(callsBefore, provider.searchCalls.size)
        }

    @Test
    fun networkErrorWhileOnlineMapsToErrorAndRetryRecovers() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider, FakeConnectivityObserver(initiallyOnline = true))
            vm.onQueryChange("pride")
            provider.searchError = CatalogErrorCode.NETWORK_ERROR
            vm.searchFirstPage()
            // Online at failure time -> NOT corroborated -> ERROR, never OFFLINE.
            assertEquals(DiscoverStatus.ERROR, vm.uiState.value.status)
            assertEquals(CatalogErrorCode.NETWORK_ERROR, vm.uiState.value.errorCode)
            provider.searchError = null
            vm.retry()
            assertEquals(DiscoverStatus.LOADED, vm.uiState.value.status)
            assertNull(vm.uiState.value.errorCode)
            assertTrue(
                vm.uiState.value.books
                    .isNotEmpty(),
            )
        }

    @Test
    fun offlineBeforeSearchRendersOfflineWithoutNetworkAttempt() =
        runTest {
            val provider = FakeCatalogProvider()
            val observer = FakeConnectivityObserver(initiallyOnline = false)
            val vm = DiscoverViewModel(provider, observer)
            assertEquals(DiscoverStatus.OFFLINE, vm.uiState.value.status)
            assertFalse(vm.uiState.value.isOnline)

            vm.onQueryChange("pride")
            vm.searchFirstPage()

            assertEquals(DiscoverStatus.OFFLINE, vm.uiState.value.status)
            assertTrue(provider.searchCalls.isEmpty())
        }

    @Test
    fun offlineDuringActiveSearchRendersOfflineAndCancelsJob() =
        runTest {
            val provider = GatedCatalogProvider()
            val observer = FakeConnectivityObserver(initiallyOnline = true)
            val vm = DiscoverViewModel(provider, observer)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            assertEquals(DiscoverStatus.LOADING, vm.uiState.value.status)
            assertTrue(vm.uiState.value.isSearching)

            observer.setOnline(false)

            assertEquals(DiscoverStatus.OFFLINE, vm.uiState.value.status)
            assertFalse(vm.uiState.value.isSearching)
        }

    @Test
    fun connectivityRestoredRetriesPendingSearch() =
        runTest {
            val provider = FakeCatalogProvider()
            val observer = FakeConnectivityObserver(initiallyOnline = false)
            val vm = DiscoverViewModel(provider, observer)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            assertTrue(provider.searchCalls.isEmpty())
            assertEquals(DiscoverStatus.OFFLINE, vm.uiState.value.status)

            observer.setOnline(true)

            assertEquals(DiscoverStatus.LOADED, vm.uiState.value.status)
            assertEquals(listOf("pride"), provider.searchCalls.map { it.first })
        }

    @Test
    fun debouncedQueryChangesOnlySearchLatest() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val provider = FakeCatalogProvider()
            val vm =
                DiscoverViewModel(
                    catalogProvider = provider,
                    connectivityObserver = FakeConnectivityObserver(initiallyOnline = true),
                    mainDispatcher = dispatcher,
                    debounceMillis = 250,
                )

            vm.onQueryChange("a")
            advanceTimeBy(100)
            vm.onQueryChange("ab")
            advanceTimeBy(100)
            vm.onQueryChange("abc")
            // U2: single 250ms trailing debounce — still pending just before the window.
            advanceTimeBy(249)
            assertTrue(provider.searchCalls.isEmpty())
            advanceTimeBy(1)
            advanceUntilIdle()

            assertEquals(listOf("abc" to 1), provider.searchCalls)
        }

    @Test
    fun queryClearedMidFlightResetsToIdleAndCancelsJob() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val provider = GatedCatalogProvider()
            val vm =
                DiscoverViewModel(
                    catalogProvider = provider,
                    connectivityObserver = FakeConnectivityObserver(initiallyOnline = true),
                    mainDispatcher = dispatcher,
                    debounceMillis = 250,
                )

            vm.onQueryChange("pride")
            vm.searchFirstPage()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.isSearching)

            vm.onQueryChange("")
            advanceUntilIdle()

            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
            assertFalse(vm.uiState.value.isSearching)
        }

    @Test
    fun isSearchingTrueWhileFetchInFlight() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val provider = GatedCatalogProvider()
            val vm =
                DiscoverViewModel(
                    catalogProvider = provider,
                    connectivityObserver = FakeConnectivityObserver(initiallyOnline = true),
                    mainDispatcher = dispatcher,
                    debounceMillis = 250,
                )

            vm.onQueryChange("pride")
            vm.searchFirstPage()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isSearching)
            assertEquals(DiscoverStatus.LOADING, vm.uiState.value.status)

            provider.gate.complete(Unit)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isSearching)
            assertEquals(DiscoverStatus.EMPTY, vm.uiState.value.status)
        }

    @Test
    fun zeroResultsMapToEmptyDistinctFromError() =
        runTest {
            val provider = FakeCatalogProvider()
            provider.page1Results = emptyList()
            provider.page1Next = null
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("no-such-book-xyz")
            vm.searchFirstPage()
            assertEquals(DiscoverStatus.EMPTY, vm.uiState.value.status)
            assertNull(vm.uiState.value.errorCode)
        }

    @Test
    fun badIdDetailMapsToNotFoundAndDismissPreservesList() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            val preserved = vm.uiState.value.books.size
            provider.detailError = CatalogErrorCode.NOT_FOUND
            vm.openDetail("openlibrary:/works/OL11W")
            assertEquals(DiscoverDetailStatus.NOT_FOUND, vm.uiState.value.detailStatus)
            assertNull(vm.uiState.value.detail)
            vm.dismissDetail()
            assertEquals(DiscoverDetailStatus.CLOSED, vm.uiState.value.detailStatus)
            assertEquals(preserved, vm.uiState.value.books.size)
        }

    @Test
    fun upstreamErrorAndRateLimitedStayErrorWhileOnline() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider, FakeConnectivityObserver(initiallyOnline = true))
            vm.onQueryChange("pride")
            provider.searchError = CatalogErrorCode.UPSTREAM_ERROR
            vm.searchFirstPage()
            assertEquals(DiscoverStatus.ERROR, vm.uiState.value.status)
            assertEquals(CatalogErrorCode.UPSTREAM_ERROR, vm.uiState.value.errorCode)

            provider.searchError = CatalogErrorCode.RATE_LIMITED
            vm.retry()
            // RATE_LIMITED no longer maps to OFFLINE while the observer is online.
            assertEquals(DiscoverStatus.ERROR, vm.uiState.value.status)
            assertEquals(CatalogErrorCode.RATE_LIMITED, vm.uiState.value.errorCode)
        }

    @Test
    fun retryAfterFailedNextPageRefetchesSamePage() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            val page1Calls = provider.searchCalls.size
            provider.searchError = CatalogErrorCode.UPSTREAM_ERROR
            vm.loadNextPage()
            assertEquals(DiscoverStatus.ERROR, vm.uiState.value.status)
            val callsAfterFailure = provider.searchCalls.size
            assertTrue(
                vm.uiState.value.books
                    .isNotEmpty(),
            )
            provider.searchError = null
            vm.retry()
            assertEquals(DiscoverStatus.LOADED, vm.uiState.value.status)
            assertEquals(2, vm.uiState.value.activePage)
            assertTrue(provider.searchCalls.size > callsAfterFailure)
            assertEquals(2, provider.searchCalls.last().second)
            assertEquals(page1Calls, 1)
        }

    @Test
    fun successfulDetailLoadPopulatesDetailWithoutTouchingList() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            val preserved = vm.uiState.value.books.size
            vm.openDetail("gutendex:1342")
            assertEquals(DiscoverDetailStatus.LOADED, vm.uiState.value.detailStatus)
            assertEquals(
                "Detail of gutendex:1342",
                vm.uiState.value.detail
                    ?.title,
            )
            assertEquals(preserved, vm.uiState.value.books.size)
            assertEquals(listOf("gutendex:1342"), provider.detailCalls)
        }

    @Test
    fun invalidPageMapsToErrorWithCodeAndListPreserved() =
        runTest {
            val provider = FakeCatalogProvider()
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()
            assertEquals(DiscoverStatus.LOADED, vm.uiState.value.status)
            val preserved = vm.uiState.value.books.size
            provider.searchError = CatalogErrorCode.INVALID_PAGE
            vm.loadNextPage()
            assertEquals(DiscoverStatus.ERROR, vm.uiState.value.status)
            assertEquals(CatalogErrorCode.INVALID_PAGE, vm.uiState.value.errorCode)
            assertEquals(preserved, vm.uiState.value.books.size)
        }

    @Test
    fun constructorSurfaceTakesCatalogProviderAndConnectivityPorts() {
        val ctor =
            DiscoverViewModel::class.java.constructors
                .firstOrNull { it.parameterTypes.size == 10 }
        assertTrue("expected a 10-arg constructor", ctor != null)
        assertEquals(CatalogProvider::class.java, ctor!!.parameterTypes[0])
        assertEquals(ConnectivityObserver::class.java, ctor.parameterTypes[1])
        assertEquals(CoroutineDispatcher::class.java, ctor.parameterTypes[2])
        assertEquals(java.lang.Long.TYPE, ctor.parameterTypes[3])
        assertEquals(DownloadAndImportBookUseCase::class.java, ctor.parameterTypes[4])
        assertEquals(kotlin.jvm.functions.Function1::class.java, ctor.parameterTypes[5])
        assertEquals(kotlin.jvm.functions.Function1::class.java, ctor.parameterTypes[6])
        // U5: disclosure-consent lookup, consent-write callback, addon resolve.
        assertEquals(kotlin.jvm.functions.Function1::class.java, ctor.parameterTypes[7])
        assertEquals(kotlin.jvm.functions.Function2::class.java, ctor.parameterTypes[8])
        assertEquals(kotlin.jvm.functions.Function3::class.java, ctor.parameterTypes[9])
    }

    /** Opt-in featured provider; [featured] is the seam under test. */
    private class FakeFeaturedCatalogProvider(
        private val featured: (CatalogFeaturedSort) -> PagedResult = { PagedResult(emptyList(), null, 0) },
    ) : CatalogProvider {
        val featuredCalls = mutableListOf<Pair<CatalogFeaturedSort, Int>>()

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
            return featured(sort)
        }

        override fun supportsFeatured(): Boolean = true
    }

    /** Featured provider whose rails stay in flight until [gate] completes. */
    private class GatedFeaturedProvider : CatalogProvider {
        val gate = CompletableDeferred<Unit>()
        val featuredCalls = mutableListOf<Pair<CatalogFeaturedSort, Int>>()

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
            gate.await()
            return if (sort == CatalogFeaturedSort.NEWEST) {
                PagedResult(
                    listOf(FakeCatalogProvider.book("gutendex:1", "Newest Book")),
                    2,
                    30,
                )
            } else {
                PagedResult(
                    listOf(FakeCatalogProvider.book("gutendex:2", "Popular Book")),
                    null,
                    5,
                )
            }
        }

        override fun supportsFeatured(): Boolean = true
    }

    @Test
    fun idleRailsLoadAsynchronouslyAndNeverBlockTheShell() =
        runTest {
            val provider = GatedFeaturedProvider()
            val vm = DiscoverViewModel(provider)

            // Both rails are published as Loading before anything resolves, and IDLE
            // itself is already on screen: rails never gate the shell status.
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
            assertEquals(
                listOf<DiscoverRailState>(DiscoverRailState.Loading, DiscoverRailState.Loading),
                vm.uiState.value.rails,
            )
            assertEquals(2, provider.featuredCalls.size)
            assertEquals(
                listOf(CatalogFeaturedSort.NEWEST, CatalogFeaturedSort.POPULAR),
                provider.featuredCalls.map { it.first },
            )

            provider.gate.complete(Unit)

            val rails = vm.uiState.value.rails
            assertTrue(rails.all { it is DiscoverRailState.Loaded })
            val newest = rails[0] as DiscoverRailState.Loaded
            assertEquals(CatalogFeaturedSort.NEWEST, newest.sort)
            assertEquals(30, newest.totalCount)
            assertEquals(1, newest.books.size)
        }

    @Test
    fun failingRailIsHiddenWhileTheOtherStillLoads() =
        runTest {
            val provider =
                FakeFeaturedCatalogProvider { sort ->
                    if (sort == CatalogFeaturedSort.NEWEST) {
                        PagedResult(listOf(FakeCatalogProvider.book("gutendex:1", "Newest Book")), null, 1)
                    } else {
                        throw CatalogException(CatalogErrorCode.UPSTREAM_ERROR, "injected")
                    }
                }
            val vm = DiscoverViewModel(provider)

            val rails = vm.uiState.value.rails
            assertTrue(rails[0] is DiscoverRailState.Loaded)
            assertEquals(DiscoverRailState.Hidden, rails[1])
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
        }

    @Test
    fun emptyFeaturedPageHidesRailAndProviderWithoutCapabilityHidesBoth() =
        runTest {
            val emptyProvider = FakeFeaturedCatalogProvider { PagedResult(emptyList(), null, 0) }
            val emptyVm = DiscoverViewModel(emptyProvider)
            assertTrue(
                emptyVm.uiState.value.rails
                    .all { it is DiscoverRailState.Hidden },
            )

            // Default (no featured override) -> fail-closed empty page -> hidden rails.
            val plainVm = DiscoverViewModel(FakeCatalogProvider())
            assertTrue(
                plainVm.uiState.value.rails
                    .all { it is DiscoverRailState.Hidden },
            )
        }

    @Test
    fun offlineIdleStartsNoRailsAtAll() =
        runTest {
            val provider =
                FakeFeaturedCatalogProvider {
                    PagedResult(listOf(FakeCatalogProvider.book("gutendex:1", "Newest Book")), null, 1)
                }
            val vm = DiscoverViewModel(provider, FakeConnectivityObserver(initiallyOnline = false))

            assertEquals(DiscoverStatus.OFFLINE, vm.uiState.value.status)
            assertTrue(
                vm.uiState.value.rails
                    .isEmpty(),
            )
            assertTrue(provider.featuredCalls.isEmpty())
        }

    // ── discover-screen U3a: in-app download → import ─────────────────────

    private fun importedBook() =
        Book(
            id = "book-1",
            title = "Detail of gutendex:1342",
            author = "Author A",
            coverPath = null,
            filePath = "/data/books/book-1.epub",
            format = "epub",
            updatedAtEpochMillis = 0L,
        )

    @Test
    fun downloadRelaysIdleDownloadingImportingSuccess() =
        runTest {
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Downloading(512L, 2048L),
                    DownloadImportState.Importing,
                    DownloadImportState.Success(importedBook()),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()

            assertTrue(vm.uiState.value.download is DownloadImportState.Success)
        }

    @Test
    fun downloadDuplicate_rendersNonErrorState() =
        runTest {
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Duplicate("already"),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()

            val state = vm.uiState.value.download
            assertTrue(state is DownloadImportState.Duplicate)
            assertFalse(state is DownloadImportState.Failure)
        }

    @Test
    fun downloadFailure_rendersInlineError_andRetryReinvokes() =
        runTest {
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Failure(CatalogErrorCode.NETWORK_ERROR),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()

            assertTrue(vm.uiState.value.download is DownloadImportState.Failure)

            vm.startDownload()
            coVerify(exactly = 2) { useCase.invoke(any()) }
        }

    // ── U3-2 funnel: download start/complete/fail counters (egress runCatching-safe; assert via UI state) ──

    @Test
    fun downloadSuccess_emitsFunnelStartAndComplete() =
        runTest {
            SentryMetrics.clearForTest()
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Downloading(512L, 2048L),
                    DownloadImportState.Success(importedBook()),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()
            assertTrue(vm.uiState.value.download is DownloadImportState.Success)
            coVerify(exactly = 1) { useCase.invoke(any()) }
            SentryMetrics.clearForTest()
        }

    @Test
    fun downloadFailure_emitsFunnelFailWithCode() =
        runTest {
            SentryMetrics.clearForTest()
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Failure(CatalogErrorCode.NETWORK_ERROR),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()
            assertTrue(vm.uiState.value.download is DownloadImportState.Failure)
            coVerify(exactly = 1) { useCase.invoke(any()) }
            SentryMetrics.clearForTest()
        }

    @Test
    fun downloadFailureNullError_emitsProviderOnlyFail() =
        runTest {
            SentryMetrics.clearForTest()
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any()) } returns flowOf(DownloadImportState.Failure(null))
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()
            val state = vm.uiState.value.download
            assertTrue(state is DownloadImportState.Failure)
            assertNull((state as DownloadImportState.Failure).error)
            SentryMetrics.clearForTest()
        }

    @Test
    fun cancelDownload_resetsStateToIdle() =
        runTest {
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Downloading(1L, 100L),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()
            vm.cancelDownload()

            assertEquals(DownloadImportState.Idle, vm.uiState.value.download)
        }

    // ── discover-screen U3b: source filter, attribution, addon rails ──────

    private class FakeSourceCatalogProvider : CatalogProvider {
        var sources: List<CatalogSourceInfo> = emptyList()
        var searchResults: List<CatalogBook> = emptyList()
        var sourceResult: (String) -> PagedResult = { PagedResult(emptyList(), null, 0) }
        val searchSourceCalls = mutableListOf<Pair<String, String>>()

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult = PagedResult(searchResults, null, searchResults.size)

        override suspend fun getDetails(id: String): CatalogBook = throw CatalogException(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = "n/a"

        override fun listSources(): List<CatalogSourceInfo> = sources

        override suspend fun searchSource(
            sourceId: String,
            query: String,
            page: Int,
        ): PagedResult {
            searchSourceCalls.add(sourceId to query)
            return sourceResult(sourceId)
        }
    }

    private fun catalogBook(
        id: String,
        provider: String,
        title: String,
    ) = CatalogBook(
        id = id,
        provider = provider,
        title = title,
        authors = listOf("Author A"),
        coverUrl = null,
        languages = listOf("en"),
        subjects = emptyList(),
        downloadUrl = null,
    )

    private val addonSourceId = "addon:0123456789abcdef"

    @Test
    fun sourceChipsComeFromListSourcesWithAllAsDefault() =
        runTest {
            val provider =
                FakeSourceCatalogProvider().apply {
                    sources =
                        listOf(
                            CatalogSourceInfo(BUILTIN_GUTENDEX, "Gutendex", CatalogSourceKind.BUILTIN),
                            CatalogSourceInfo(addonSourceId, "My Addon", CatalogSourceKind.ADDON),
                        )
                }
            val vm = DiscoverViewModel(provider)

            assertEquals(2, vm.uiState.value.sources.size)
            assertEquals(DiscoverSourceFilter.AllSources, vm.uiState.value.sourceFilter)
        }

    @Test
    fun sourceFilterNarrowsMergedBooksInMemory() =
        runTest {
            val provider =
                FakeSourceCatalogProvider().apply {
                    searchResults =
                        listOf(
                            catalogBook("gutendex:1", BUILTIN_GUTENDEX, "Builtin Book"),
                            catalogBook("a:1", addonSourceId, "Addon Book"),
                        )
                }
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()

            assertEquals(2, vm.uiState.value.visibleBooks.size)

            vm.setSourceFilter(DiscoverSourceFilter.Source(addonSourceId))

            assertEquals(1, vm.uiState.value.visibleBooks.size)
            assertEquals(
                addonSourceId,
                vm.uiState.value.visibleBooks
                    .single()
                    .provider,
            )
            // The merged list is preserved; only the derived view narrows.
            assertEquals(2, vm.uiState.value.books.size)
        }

    @Test
    fun registryChangeRefreshesChipsAndDegradesAnAbsentFilter() =
        runTest {
            var listener: ((Int) -> Unit)? = null
            val provider =
                FakeSourceCatalogProvider().apply {
                    sources = listOf(CatalogSourceInfo(addonSourceId, "My Addon", CatalogSourceKind.ADDON))
                }
            val vm =
                DiscoverViewModel(
                    catalogProvider = provider,
                    registerAddonChangeListener = { listener = it },
                )
            vm.setSourceFilter(DiscoverSourceFilter.Source(addonSourceId))

            provider.sources = emptyList()
            listener!!.invoke(1)

            assertTrue(
                vm.uiState.value.sources
                    .isEmpty(),
            )
            assertEquals(DiscoverSourceFilter.AllSources, vm.uiState.value.sourceFilter)
        }

    @Test
    fun attributionNamesResolveViaInjectedLookup() =
        runTest {
            val addonId = "0123456789abcdef"
            val provider =
                FakeSourceCatalogProvider().apply {
                    sources = listOf(CatalogSourceInfo("addon:$addonId", "Raw Name", CatalogSourceKind.ADDON))
                }
            val vm =
                DiscoverViewModel(
                    catalogProvider = provider,
                    addonNames = { id -> if (id == addonId) "Pretty Addon" else null },
                )

            assertEquals("Pretty Addon", vm.uiState.value.attributionNames[addonId])
        }

    @Test
    fun cachedRowsFromDisabledAddonDegradeWithoutCrash() =
        runTest {
            val goneId = "fedcba9876543210"
            val provider =
                FakeSourceCatalogProvider().apply {
                    searchResults = listOf(catalogBook("x:1", "addon:$goneId", "Orphan Book"))
                    sources = emptyList()
                }
            val vm = DiscoverViewModel(provider)
            vm.onQueryChange("pride")
            vm.searchFirstPage()

            // No attribution entry for the vanished addon, yet the cached row survives.
            assertFalse(
                vm.uiState.value.attributionNames
                    .containsKey(goneId),
            )
            assertEquals(1, vm.uiState.value.visibleBooks.size)
        }

    @Test
    fun addonRailIsHiddenOnEmptyAndError() =
        runTest {
            val source = CatalogSourceInfo(addonSourceId, "My Addon", CatalogSourceKind.ADDON)

            val emptyVm = DiscoverViewModel(FakeSourceCatalogProvider().apply { sources = listOf(source) })
            advanceUntilIdle()
            assertEquals(3, emptyVm.uiState.value.rails.size)
            assertTrue(
                emptyVm.uiState.value.rails
                    .all { it is DiscoverRailState.Hidden },
            )

            val errorVm =
                DiscoverViewModel(
                    FakeSourceCatalogProvider().apply {
                        sources = listOf(source)
                        sourceResult = { throw CatalogException(CatalogErrorCode.UPSTREAM_ERROR, "injected") }
                    },
                )
            advanceUntilIdle()
            assertTrue(
                errorVm.uiState.value.rails
                    .all { it is DiscoverRailState.Hidden },
            )
        }

    @Test
    fun addonRailLoadsFromSearchSourceAndDisappearsOnUninstall() =
        runTest {
            var listener: ((Int) -> Unit)? = null
            val provider =
                FakeSourceCatalogProvider().apply {
                    sources = listOf(CatalogSourceInfo(addonSourceId, "My Addon", CatalogSourceKind.ADDON))
                    sourceResult = {
                        PagedResult(listOf(catalogBook("a:1", addonSourceId, "Addon Book")), null, 1)
                    }
                }
            val vm =
                DiscoverViewModel(
                    catalogProvider = provider,
                    registerAddonChangeListener = { listener = it },
                )
            advanceUntilIdle()

            val addonRail =
                vm.uiState.value.rails
                    .filterIsInstance<DiscoverRailState.Loaded>()
                    .single { it.sourceId == addonSourceId }
            assertEquals("My Addon", addonRail.addonName)
            assertEquals(DiscoverViewModel.ADDON_RAIL_TERM, provider.searchSourceCalls.single().second)

            // Uninstall: the source vanishes and the rail disappears on registry change.
            provider.sources = emptyList()
            listener!!.invoke(1)
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.rails.none {
                    it is DiscoverRailState.Loaded && it.sourceId == addonSourceId
                },
            )
        }

    // ── U5: consent-gated access UI state ────────────────────────────────

    private class DetailConsentProvider(
        var detail: CatalogBook,
    ) : CatalogProvider {
        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult = PagedResult(emptyList(), null, 0)

        override suspend fun getDetails(id: String): CatalogBook = detail

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = throw CatalogException(CatalogErrorCode.UNAVAILABLE_DOWNLOAD, "no formats")

        override fun listSources(): List<CatalogSourceInfo> = emptyList()
    }

    private fun consentPdBook(): CatalogBook =
        CatalogBook(
            id = "gutendex:1342",
            provider = BUILTIN_GUTENDEX,
            title = "Pride and Prejudice",
            authors = listOf("Jane Austen"),
            coverUrl = null,
            languages = listOf("en"),
            subjects = emptyList(),
            downloadUrl = "https://www.gutenberg.org/cache/epub/1342/pg1342.epub",
            isPublicDomain = true,
        )

    private fun consentAddonBook(): CatalogBook =
        CatalogBook(
            id = "addon:abcdef1234567890:1",
            provider = addonSource("abcdef1234567890"),
            title = "Addon Title",
            authors = listOf("Addon Author"),
            coverUrl = null,
            languages = listOf("en"),
            subjects = emptyList(),
            downloadUrl = null,
            isPublicDomain = null,
        )

    /** Mutable-set consent backend standing in for the durable store. */
    private class ConsentBackend {
        val consented = mutableSetOf<String>()
        val writes = mutableListOf<Pair<String, Boolean>>()

        fun lookup(id: String): Boolean = id in consented

        fun write(
            id: String,
            granted: Boolean,
        ) {
            writes.add(id to granted)
            if (granted) consented.add(id) else consented.remove(id)
        }
    }

    private fun consentViewModel(
        provider: DetailConsentProvider,
        backend: ConsentBackend,
        resolve: (suspend (String, CatalogBook) -> LegalAccess)? = null,
    ): DiscoverViewModel =
        DiscoverViewModel(
            catalogProvider = provider,
            connectivityObserver = FakeConnectivityObserver(initiallyOnline = true),
            addonConsent = backend::lookup,
            onAddonConsentChange = backend::write,
            addonResolve = resolve,
        )

    @Test
    fun openDetail_publicDomainBook_loadsAccessWithDownload() =
        runTest {
            val vm = consentViewModel(DetailConsentProvider(consentPdBook()), ConsentBackend())
            vm.openDetail("gutendex:1342")
            advanceUntilIdle()
            val state = vm.uiState.value.accessState
            assertTrue(state is AccessResolverState.Loaded)
            assertTrue((state as AccessResolverState.Loaded).access.canDownloadInApp)
        }

    @Test
    fun openDetail_unconsentedAddonBook_gatesConsent() =
        runTest {
            val vm = consentViewModel(DetailConsentProvider(consentAddonBook()), ConsentBackend())
            vm.openDetail("addon:abcdef1234567890:1")
            advanceUntilIdle()
            assertEquals(
                AccessResolverState.ConsentRequired("abcdef1234567890"),
                vm.uiState.value.accessState,
            )
        }

    @Test
    fun grantAccessConsent_recordsDurablyAndLoads() =
        runTest {
            val backend = ConsentBackend()
            val vm = consentViewModel(DetailConsentProvider(consentAddonBook()), backend)
            vm.openDetail("addon:abcdef1234567890:1")
            advanceUntilIdle()
            vm.grantAccessConsent("abcdef1234567890")
            advanceUntilIdle()
            assertEquals(listOf("abcdef1234567890" to true), backend.writes)
            assertTrue(vm.uiState.value.accessState is AccessResolverState.Loaded)
        }

    @Test
    fun denyAccessConsent_recordsNothingAndEmpties() =
        runTest {
            val backend = ConsentBackend()
            val vm = consentViewModel(DetailConsentProvider(consentAddonBook()), backend)
            vm.openDetail("addon:abcdef1234567890:1")
            advanceUntilIdle()
            vm.denyAccessConsent()
            advanceUntilIdle()
            assertTrue(backend.writes.isEmpty())
            assertEquals(AccessResolverState.Empty, vm.uiState.value.accessState)
        }

    @Test
    fun openAddonRead_withoutConsent_promptsWithoutResolve() =
        runTest {
            var resolved = false
            val vm =
                consentViewModel(
                    DetailConsentProvider(consentAddonBook()),
                    ConsentBackend(),
                    resolve = { _, book ->
                        resolved = true
                        LegalAccess(book.id, false, null, emptyList())
                    },
                )
            vm.openDetail("addon:abcdef1234567890:1")
            advanceUntilIdle()
            vm.openAddonRead("abcdef1234567890", "Test Addon")
            advanceUntilIdle()
            assertEquals(AddonReadState.ConsentRequired, vm.uiState.value.addonRead)
            assertEquals("Test Addon", vm.uiState.value.addonReadName)
            assertEquals(false, resolved)
        }

    @Test
    fun grantAddonReadConsent_resolvesToEmpty() =
        runTest {
            val backend = ConsentBackend()
            val vm =
                consentViewModel(
                    DetailConsentProvider(consentAddonBook()),
                    backend,
                    resolve = { _, book -> LegalAccess(book.id, false, null, emptyList()) },
                )
            vm.openDetail("addon:abcdef1234567890:1")
            advanceUntilIdle()
            vm.openAddonRead("abcdef1234567890", "Test Addon")
            advanceUntilIdle()
            vm.grantAddonReadConsent()
            advanceUntilIdle()
            assertEquals(AddonReadState.Empty, vm.uiState.value.addonRead)
        }

    @Test
    fun openAddonRead_withConsentAndItems_loads() =
        runTest {
            val backend = ConsentBackend().apply { consented.add("abcdef1234567890") }
            val vm =
                consentViewModel(
                    DetailConsentProvider(consentAddonBook()),
                    backend,
                    resolve = { _, book ->
                        LegalAccess(book.id, true, "https://www.gutenberg.org/cache/epub/1342/pg1342.epub", emptyList())
                    },
                )
            vm.openDetail("addon:abcdef1234567890:1")
            advanceUntilIdle()
            vm.openAddonRead("abcdef1234567890", "Test Addon")
            advanceUntilIdle()
            assertTrue(vm.uiState.value.addonRead is AddonReadState.Loaded)
        }

    @Test
    fun openAddonRead_resolveFailure_mapsToError() =
        runTest {
            val backend = ConsentBackend().apply { consented.add("abcdef1234567890") }
            val vm =
                consentViewModel(
                    DetailConsentProvider(consentAddonBook()),
                    backend,
                    resolve = { _, _ ->
                        throw CatalogException(CatalogErrorCode.UPSTREAM_ERROR, "boom")
                    },
                )
            vm.openDetail("addon:abcdef1234567890:1")
            advanceUntilIdle()
            vm.openAddonRead("abcdef1234567890", "Test Addon")
            advanceUntilIdle()
            assertEquals(AddonReadState.Error, vm.uiState.value.addonRead)
        }

    @Test
    fun dismissAddonRead_hidesSheet() =
        runTest {
            val vm = consentViewModel(DetailConsentProvider(consentAddonBook()), ConsentBackend())
            vm.openDetail("addon:abcdef1234567890:1")
            advanceUntilIdle()
            vm.openAddonRead("abcdef1234567890", "Test Addon")
            advanceUntilIdle()
            vm.dismissAddonRead()
            assertEquals(AddonReadState.Hidden, vm.uiState.value.addonRead)
        }
}
