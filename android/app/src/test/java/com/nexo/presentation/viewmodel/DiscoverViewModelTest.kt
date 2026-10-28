package com.nexo.presentation.viewmodel

import com.nexo.R
import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.BUILTIN_OPENLIBRARY
import com.nexo.data.remote.catalog.BUILTIN_STANDARD_EBOOKS
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
import com.nexo.domain.discover.entriesFor
import com.nexo.domain.model.Book
import com.nexo.domain.session.ActiveUserProvider
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
import kotlinx.coroutines.awaitCancellation
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
import java.time.LocalDate

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

            /** Broad fake search page; the rails no longer key on an author credit. */
            val SEARCH_BOOKS = listOf("Author A")
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
                .firstOrNull { it.parameterTypes.size == 11 }
        assertTrue("expected an 11-arg constructor", ctor != null)
        assertEquals(CatalogProvider::class.java, ctor!!.parameterTypes[0])
        assertEquals(ConnectivityObserver::class.java, ctor.parameterTypes[1])
        assertEquals(CoroutineDispatcher::class.java, ctor.parameterTypes[2])
        assertEquals(java.lang.Long.TYPE, ctor.parameterTypes[3])
        assertEquals(DownloadAndImportBookUseCase::class.java, ctor.parameterTypes[4])
        // Account isolation: the active-account source sits before the addon hooks.
        assertEquals(ActiveUserProvider::class.java, ctor.parameterTypes[5])
        assertEquals(kotlin.jvm.functions.Function1::class.java, ctor.parameterTypes[6])
        assertEquals(kotlin.jvm.functions.Function1::class.java, ctor.parameterTypes[7])
        // U5: disclosure-consent lookup, consent-write callback, addon resolve.
        assertEquals(kotlin.jvm.functions.Function1::class.java, ctor.parameterTypes[8])
        assertEquals(kotlin.jvm.functions.Function2::class.java, ctor.parameterTypes[9])
        assertEquals(kotlin.jvm.functions.Function3::class.java, ctor.parameterTypes[10])
    }

    /**
     * Rail provider: one Gutendex topic page per term, plus the two fixed-source
     * shelves the plan also asks for (the closed featured ordering and the
     * Standard Ebooks source).
     */
    private class FakeRailProvider(
        private val resultsFor: (String) -> PagedResult = { PagedResult(emptyList(), null, 0) },
        private val featuredResults: PagedResult = PagedResult(emptyList(), null, 0),
        private val standardEbooksResults: PagedResult = PagedResult(emptyList(), null, 0),
        private val searchResults: PagedResult = PagedResult(emptyList(), null, 0),
    ) : CatalogProvider {
        val searchSourceCalls = mutableListOf<Pair<String, String>>()
        val featuredCalls = mutableListOf<CatalogFeaturedSort>()

        /** Terms that reached the `?topic=` door; the only door a topic shelf may use. */
        val topicCalls = mutableListOf<String>()

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult = searchResults

        override suspend fun featured(
            sort: CatalogFeaturedSort,
            page: Int,
        ): PagedResult {
            featuredCalls.add(sort)
            return featuredResults
        }

        override fun supportsFeatured(): Boolean = true

        override suspend fun topic(
            term: String,
            page: Int,
        ): PagedResult {
            topicCalls.add(term)
            return resultsFor(term)
        }

        override fun supportsTopic(): Boolean = true

        override suspend fun getDetails(id: String): CatalogBook = throw CatalogException(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = "n/a"

        override fun listSources(): List<CatalogSourceInfo> = emptyList()

        override suspend fun searchSource(
            sourceId: String,
            query: String,
            page: Int,
        ): PagedResult {
            searchSourceCalls.add(sourceId to query)
            return when (sourceId) {
                BUILTIN_GUTENDEX -> PagedResult(emptyList(), null, 0)
                BUILTIN_STANDARD_EBOOKS -> standardEbooksResults
                else -> PagedResult(emptyList(), null, 0)
            }
        }
    }

    /** Rail provider stalled for `railTerm`: that shelf stays Loading, the rest fail closed. */
    private class StallingRailProvider(
        private val railTerm: String,
    ) : CatalogProvider {
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

        /** The topic shelves' door: the selected rail never emits. */
        override suspend fun topic(
            term: String,
            page: Int,
        ): PagedResult {
            if (term == railTerm) awaitCancellation()
            throw CatalogException(CatalogErrorCode.UPSTREAM_ERROR, "injected")
        }

        override fun supportsTopic(): Boolean = true
    }

    /**
     * Gated rail provider: every IDLE shelf search blocks on [gate], so the
     * published rails stay in [DiscoverRailState.Loading] until it completes.
     * The gate sits on `topic` — the seam the IDLE topic shelves use.
     */
    private class GatedFeaturedProvider : CatalogProvider {
        val gate = CompletableDeferred<Unit>()

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

        override suspend fun searchSource(
            sourceId: String,
            query: String,
            page: Int,
        ): PagedResult {
            gate.await()
            return PagedResult(emptyList(), null, 0)
        }
    }

    /**
     * Rail page for [term]: one Gutendex book carrying that topic term as its id
     * suffix, the way the provider answers a `?topic=` search.
     */
    private fun topicPage(term: String) =
        PagedResult(
            listOf(
                CatalogBook(
                    id = "gutendex:${term.replace(' ', '-')}",
                    provider = BUILTIN_GUTENDEX,
                    title = "A book about $term",
                    authors = listOf("Some Author"),
                    coverUrl = "https://covers.example/${term.replace(' ', '-')}.jpg",
                    languages = listOf("en"),
                    subjects = emptyList(),
                    downloadUrl = null,
                ),
            ),
            nexoPage = null,
            totalCount = 1,
        )

    /** One rail card; [coverUrl] null is the coverless case rails now render as a text card. */
    private fun railBook(
        id: String,
        provider: String,
        title: String,
        coverUrl: String?,
        authors: List<String> = listOf("Some Author"),
    ) = CatalogBook(
        id = id,
        provider = provider,
        title = title,
        authors = authors,
        coverUrl = coverUrl,
        languages = listOf("en"),
        subjects = emptyList(),
        downloadUrl = null,
    )

    /** Gutendex popular shelf page. */
    private fun popularPage() =
        PagedResult(
            listOf(
                railBook("gutendex:1342", BUILTIN_GUTENDEX, "Pride and Prejudice", "https://covers.example/1342.jpg"),
                railBook("gutendex:84", BUILTIN_GUTENDEX, "Frankenstein", "https://covers.example/84.jpg"),
            ),
            nexoPage = 2,
            totalCount = 100,
        )

    /** Standard Ebooks newest-releases shelf page. */
    private fun standardEbooksPage() =
        PagedResult(
            listOf(
                railBook(
                    "standard-ebooks/henry-seton-merriman/suspense",
                    BUILTIN_STANDARD_EBOOKS,
                    "Suspense",
                    "https://standardebooks.org/ebooks/henry-seton-merriman/suspense/downloads/cover-thumbnail.jpg",
                    authors = listOf("Henry Seton Merriman"),
                ),
            ),
            nexoPage = null,
            totalCount = 1,
        )

    /** Real resource id per rotation term — the same table the rail titles use. */
    private fun topicTitleResFor(term: String): Int =
        when (term) {
            "science fiction" -> R.string.discover_rail_topic_science_fiction
            "adventure" -> R.string.discover_rail_topic_adventure
            "history" -> R.string.discover_rail_topic_history
            "travel" -> R.string.discover_rail_topic_travel
            "poetry" -> R.string.discover_rail_topic_poetry
            "philosophy" -> R.string.discover_rail_topic_philosophy
            "drama" -> R.string.discover_rail_topic_drama
            "romance" -> R.string.discover_rail_topic_romance
            "mystery" -> R.string.discover_rail_topic_mystery
            "horror" -> R.string.discover_rail_topic_horror
            else -> throw AssertionError("no title resource for term: $term")
        }

    private fun todayTerms(): List<String> = entriesFor(LocalDate.now()).map { it.term }

    @Test
    fun idleRailsLoadAsynchronouslyAndNeverBlockTheShell() =
        runTest {
            val terms = todayTerms()
            val provider =
                FakeRailProvider(
                    resultsFor = { term -> topicPage(term) },
                    featuredResults = popularPage(),
                    standardEbooksResults = standardEbooksPage(),
                )
            val vm = DiscoverViewModel(provider)

            // IDLE itself is already on screen: rails never gate the shell status.
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
            advanceUntilIdle()

            val rails = vm.uiState.value.rails
            // Four rails: two rotating authors, the Gutendex popular shelf, then
            // the Standard Ebooks shelf — index-stable design order.
            assertEquals(4, rails.size)
            assertTrue(rails.all { it is DiscoverRailState.Loaded })
            val shelves = rails.map { it as DiscoverRailState.Loaded }
            assertEquals(terms, shelves.take(2).map { it.topicTerm })
            assertTrue(shelves.take(2).all { it.sourceId == BUILTIN_GUTENDEX && it.sort == null })
            assertEquals(1, shelves.first().books.size)
            // Every shelf is titled by its own topic string resource.
            assertEquals(topicTitleResFor(terms.first()), shelves.first().sectionTitleRes)
            // The popular shelf goes through the closed featured ordering.
            assertEquals(R.string.discover_rail_gutendex_popular, shelves[2].sectionTitleRes)
            assertEquals(CatalogFeaturedSort.POPULAR, shelves[2].sort)
            assertEquals(BUILTIN_GUTENDEX, shelves[2].sourceId)
            assertEquals(listOf(CatalogFeaturedSort.POPULAR), provider.featuredCalls)
            // The Standard Ebooks shelf goes through its own source, blank term.
            assertEquals(R.string.discover_rail_standard_ebooks, shelves[3].sectionTitleRes)
            assertEquals(BUILTIN_STANDARD_EBOOKS, shelves[3].sourceId)
            assertEquals(listOf("Suspense"), shelves[3].books.map { it.title })
            // One Gutendex TOPIC query per topic shelf, one Standard Ebooks query.
            // The topic shelves must never travel through `searchSource`: that door
            // is a free-text search and answers 6 books where `?topic=` answers 3 530.
            assertEquals(terms, provider.topicCalls)
            assertTrue(provider.searchSourceCalls.none { it.first == BUILTIN_GUTENDEX })
            assertEquals(listOf(BUILTIN_STANDARD_EBOOKS to ""), provider.searchSourceCalls.filter { it.first == BUILTIN_STANDARD_EBOOKS })
        }

    @Test
    fun railsKeepCoverlessBooksAsTextCardsWhileSearchKeepsThem() =
        runTest {
            val term = todayTerms().first()
            val covered = railBook("gutendex:covered", BUILTIN_GUTENDEX, "Covered", "https://covers.example/c.jpg")
            val coverless = railBook("gutendex:coverless", BUILTIN_GUTENDEX, "Coverless", null)
            val provider =
                FakeRailProvider(
                    resultsFor = { query ->
                        if (query == term) {
                            PagedResult(listOf(covered, coverless), null, 2)
                        } else {
                            PagedResult(emptyList(), null, 0)
                        }
                    },
                    // The search grid is a text list: it must keep every match too.
                    searchResults = PagedResult(listOf(coverless), null, 1),
                )
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val shelf =
                vm.uiState.value.rails
                    .first() as DiscoverRailState.Loaded
            // The coverless book is NOT dropped: it renders a text card instead.
            assertEquals(listOf("gutendex:covered", "gutendex:coverless"), shelf.books.map { it.id })
            assertEquals(2, shelf.totalCount)

            vm.onQueryChange("coverless")
            vm.searchFirstPage()
            assertEquals(
                listOf("gutendex:coverless"),
                vm.uiState.value.books
                    .map { it.id },
            )
        }

    @Test
    fun railWhoseBooksAreAllCoverlessRendersTextCardsInsteadOfHiding() =
        runTest {
            val coverless = railBook("gutendex:no-cover", BUILTIN_GUTENDEX, "No cover", null)
            val provider =
                FakeRailProvider(
                    resultsFor = { PagedResult(listOf(coverless), null, 1) },
                    featuredResults = PagedResult(listOf(coverless.copy(id = "gutendex:1")), null, 1),
                    standardEbooksResults = PagedResult(listOf(coverless.copy(provider = BUILTIN_STANDARD_EBOOKS, id = "standard-ebooks:x/y")), null, 1),
                )
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            // All-coverless still has content to show: the section stays, as text cards.
            assertEquals(4, vm.uiState.value.rails.size)
            assertTrue(
                vm.uiState.value.rails
                    .all { it is DiscoverRailState.Loaded },
            )
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
        }

    @Test
    fun railWithNoBooksStillHidesItsSection() =
        runTest {
            val provider = FakeRailProvider(resultsFor = { PagedResult(emptyList(), null, 0) })
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            // A genuinely empty page is the only thing that hides a shelf.
            assertEquals(4, vm.uiState.value.rails.size)
            // The two topic shelves and Standard Ebooks hide; the fake's popular
            // shelf answers, so its section stays.
            assertEquals(
                listOf(
                    DiscoverRailState.Hidden,
                    DiscoverRailState.Hidden,
                    DiscoverRailState.Hidden,
                    DiscoverRailState.Hidden,
                ),
                vm.uiState.value.rails,
            )
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
        }

    @Test
    fun railScopedSectionRouteCarriesTheTopicTermAndItsSource() =
        runTest {
            // "See all" for a topic shelf must page the same `topic` query through
            // its own source, which the route encodes as sourceId + term.
            val term = todayTerms().first()
            val provider =
                FakeRailProvider(
                    resultsFor = { topicPage(it) },
                    featuredResults = PagedResult(listOf(railBook("gutendex:1", BUILTIN_GUTENDEX, "Popular", "https://covers.example/p.jpg")), null, 1),
                    standardEbooksResults = PagedResult(listOf(railBook("standard-ebooks:x/y", BUILTIN_STANDARD_EBOOKS, "New", "https://covers.example/n.jpg")), null, 1),
                )
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val shelf =
                vm.uiState.value.rails
                    .first() as DiscoverRailState.Loaded
            assertEquals(term, shelf.topicTerm)
            assertEquals(BUILTIN_GUTENDEX, shelf.sourceId)
            // The two fixed shelves carry no term, so their "See all" cannot page.
            assertNull((vm.uiState.value.rails[2] as DiscoverRailState.Loaded).topicTerm)
            assertNull((vm.uiState.value.rails[3] as DiscoverRailState.Loaded).topicTerm)
        }

    @Test
    fun topicRailPageWithNoBooksIsHiddenWhileOtherShelvesLoad() =
        runTest {
            val terms = todayTerms()
            val provider =
                FakeRailProvider(resultsFor = { query ->
                    if (query == terms[1]) {
                        PagedResult(emptyList(), null, 0)
                    } else {
                        topicPage(query)
                    }
                })
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val rails = vm.uiState.value.rails
            assertTrue(rails[0] is DiscoverRailState.Loaded)
            // Zero books fails that shelf closed instead of rendering an empty row.
            assertEquals(DiscoverRailState.Hidden, rails[1])
            assertEquals(4, rails.size)
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
        }

    @Test
    fun stallingTopicRailStaysLoadingWhileOthersResolve() =
        runTest {
            val terms = todayTerms()
            val provider = StallingRailProvider(terms[0])
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val rails = vm.uiState.value.rails
            // The stalled shelf is still in flight — neither hidden nor failed —
            // and the remaining shelves resolve independently of each other.
            assertEquals(DiscoverRailState.Loading, rails[0])
            assertEquals(4, rails.size)
            assertTrue(
                "a stalled rail must never be rendered as an empty shelf",
                rails.drop(1).none { it is DiscoverRailState.Loaded && it.books.isEmpty() },
            )
        }

    @Test
    fun emptyRailPageHidesEveryShelfWithoutFailingTheScreen() =
        runTest {
            val vm = DiscoverViewModel(FakeRailProvider())
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.rails
                    .all { it is DiscoverRailState.Hidden },
            )
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
        }

    /**
     * Bug 2, the topic half: an EMPTY `?topic=` page is not a failure. The source
     * answered and had nothing; that must never be reported as "sin conexión".
     */
    @Test
    fun emptyTopicRailResolvesToHiddenNeverToError() =
        runTest {
            val terms = todayTerms()
            val provider = FakeRailProvider(resultsFor = { PagedResult(emptyList(), null, 0) })
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val rails = vm.uiState.value.rails
            // The request really went out — this is a served empty page, not a skip.
            assertEquals(terms, provider.topicCalls)
            assertTrue(
                "an empty page must never surface an error",
                rails.none { it is DiscoverRailState.Error },
            )
            assertEquals(DiscoverRailState.Hidden, rails[0])
            assertEquals(DiscoverRailState.Hidden, rails[1])
            // No offline copy anywhere: the screen stays IDLE and online.
            assertTrue(vm.uiState.value.isOnline)
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
            assertTrue(
                vm.uiState.value.visibleRailGroupNotices
                    .isEmpty(),
            )
        }

    /**
     * Bug 2, the other half: a genuine transport failure is still an error, and a
     * connectivity code still maps to the offline pill. The empty-page fix must
     * not swallow it.
     */
    @Test
    fun transportFailureOnATopicRailStillResolvesToError() =
        runTest {
            val terms = todayTerms()
            val provider =
                FakeRailProvider(
                    resultsFor = { throw CatalogException(CatalogErrorCode.NETWORK_ERROR, "socket closed") },
                )
            val vm = DiscoverViewModel(provider, FakeConnectivityObserver(initiallyOnline = true))
            advanceUntilIdle()

            val rails = vm.uiState.value.rails
            assertEquals(terms, provider.topicCalls)
            val first = rails[0] as DiscoverRailState.Error
            assertEquals(CatalogErrorCode.NETWORK_ERROR, first.code)
            assertEquals(BUILTIN_GUTENDEX, first.sourceId)
            assertTrue(rails[1] is DiscoverRailState.Error)
        }

    @Test
    fun nonConnectivityTransportFailureOnATopicRailResolvesToErrorNotHidden() =
        runTest {
            val provider =
                FakeRailProvider(
                    resultsFor = { throw CatalogException(CatalogErrorCode.UPSTREAM_ERROR, "upstream 500") },
                )
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val first = vm.uiState.value.rails[0] as DiscoverRailState.Error
            assertEquals(CatalogErrorCode.UPSTREAM_ERROR, first.code)
            assertTrue(vm.uiState.value.isOnline)
            assertEquals(DiscoverStatus.IDLE, vm.uiState.value.status)
        }

    @Test
    fun offlineIdleStartsNoRailsAtAll() =
        runTest {
            val provider = FakeRailProvider(resultsFor = { term -> topicPage(term) })
            val vm = DiscoverViewModel(provider, FakeConnectivityObserver(initiallyOnline = false))

            assertEquals(DiscoverStatus.OFFLINE, vm.uiState.value.status)
            assertTrue(
                vm.uiState.value.rails
                    .isEmpty(),
            )
            assertTrue(provider.searchSourceCalls.isEmpty())
            assertTrue(provider.topicCalls.isEmpty())
        }

    // ── grouped rail failure notice (parity with desktop) ──────────────────

    @Test
    fun everyRailOfTheGutendexGroupFailingYieldsExactlyOneNotice() =
        runTest {
            val terms = todayTerms()
            val provider =
                FakeGroupRailProvider(
                    failingSources = setOf(BUILTIN_GUTENDEX),
                    resultsFor = { term -> topicPage(term) },
                ).apply {
                    failingPopular = true
                }
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(listOf(DiscoverRailGroup.Gutendex), state.visibleRailGroupNotices)
            // One notice for the source, never one per failed shelf.
            assertEquals(1, state.visibleRailGroupNotices.size)
            // All three Gutendex shelves are down; Standard Ebooks still works.
            assertTrue(state.rails.take(3).all { it is DiscoverRailState.Error })
            assertTrue(state.rails[3] is DiscoverRailState.Loaded)
        }

    @Test
    fun oneOfTwoTopicRailsFailingYieldsNoNotice() =
        runTest {
            val terms = todayTerms()
            val provider =
                FakeGroupRailProvider(
                    resultsFor = { term ->
                        if (term == terms.first()) {
                            throw CatalogException(CatalogErrorCode.RATE_LIMITED, "upstream 429")
                        }
                        topicPage(term)
                    },
                )
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val state = vm.uiState.value
            // The single failing shelf keeps its own inline error and retry…
            assertTrue(state.rails[0] is DiscoverRailState.Error)
            // …and the group never claims the whole source is down.
            assertTrue(state.visibleRailGroupNotices.isEmpty())
        }

    @Test
    fun noNoticeWhileTheRailsAreStillLoading() =
        runTest {
            val provider =
                FakeGroupRailProvider(failingSources = setOf(BUILTIN_GUTENDEX)).apply {
                    failingPopular = true
                }
            val vm = DiscoverViewModel(provider)

            advanceUntilIdle()
            assertEquals(listOf(DiscoverRailGroup.Gutendex), vm.uiState.value.visibleRailGroupNotices)

            // Put the group back in flight: while ANY rail of the group is still
            // Loading the source is not settled as failed, so the notice must go
            // away instead of claiming a verdict the page does not have yet.
            provider.failingSources = emptySet()
            provider.gate = CompletableDeferred()
            vm.retryRailGroup(DiscoverRailGroup.Gutendex)

            assertTrue(
                vm.uiState.value.rails
                    .take(2)
                    .all { it is DiscoverRailState.Loading },
            )
            assertTrue(
                vm.uiState.value.visibleRailGroupNotices
                    .isEmpty(),
            )

            provider.gate?.complete(Unit)
            advanceUntilIdle()
            assertTrue(
                vm.uiState.value.visibleRailGroupNotices
                    .isEmpty(),
            )
        }

    @Test
    fun retryOfAFailedGroupNeverRefetchesTheWorkingGroup() =
        runTest {
            val provider =
                FakeGroupRailProvider(failingSources = setOf(BUILTIN_GUTENDEX)).apply {
                    failingPopular = true
                }
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()
            assertEquals(listOf(DiscoverRailGroup.Gutendex), vm.uiState.value.visibleRailGroupNotices)

            provider.failingSources = emptySet()
            provider.failingPopular = false
            val gutendexBefore = provider.featuredCalls.size
            val standardEbooksBefore = provider.searchSourceCalls.count { it.first == BUILTIN_STANDARD_EBOOKS }
            val topicCallsBefore = provider.topicCalls.size

            vm.retryRailGroup(DiscoverRailGroup.Gutendex)
            advanceUntilIdle()

            // Exactly the two topic rails were asked again through `?topic=` (the
            // popular rail goes through featured, counted below)…
            assertEquals(topicCallsBefore + 2, provider.topicCalls.size)
            assertEquals(gutendexBefore + 1, provider.featuredCalls.size)
            // …and the shelf that worked was left untouched.
            assertEquals(
                standardEbooksBefore,
                provider.searchSourceCalls.count { it.first == BUILTIN_STANDARD_EBOOKS },
            )
            assertTrue(
                vm.uiState.value.visibleRailGroupNotices
                    .isEmpty(),
            )
        }

    @Test
    fun rebutOnOneRailsErrorRefetchesExactlyThatRail() =
        runTest {
            val terms = todayTerms()
            val provider =
                FakeGroupRailProvider(
                    resultsFor = { term ->
                        if (term == terms.first()) {
                            throw CatalogException(CatalogErrorCode.RATE_LIMITED, "upstream 429")
                        }
                        topicPage(term)
                    },
                )
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.rails[0] is DiscoverRailState.Error)
            assertTrue(vm.uiState.value.rails[1] is DiscoverRailState.Loaded)

            val topicCallsBefore = provider.topicCalls.size
            val siblingCallsBefore = provider.topicCalls.count { it == terms[1] }
            val featuredBefore = provider.featuredCalls.size

            vm.retryRail(0)
            advanceUntilIdle()

            // Exactly ONE topic request was issued, for the failed rail only…
            assertEquals(topicCallsBefore + 1, provider.topicCalls.size)
            // …the sibling rail kept its book WITHOUT being refetched…
            assertEquals(siblingCallsBefore, provider.topicCalls.count { it == terms[1] })
            assertTrue(vm.uiState.value.rails[1] is DiscoverRailState.Loaded)
            // …and the other sources were never touched.
            assertEquals(featuredBefore, provider.featuredCalls.size)
        }

    @Test
    fun groupedNoticeRetryRefetchesOnlyTheFailedRailsOfTheGroup() =
        runTest {
            val terms = todayTerms()
            // Rail 0 fails, rail 1 works: one rail of the group is still down.
            val provider =
                FakeGroupRailProvider(
                    resultsFor = { term ->
                        if (term == terms.first()) {
                            throw CatalogException(CatalogErrorCode.RATE_LIMITED, "upstream 429")
                        }
                        topicPage(term)
                    },
                )
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val siblingCallsBefore = provider.topicCalls.count { it == terms[1] }
            val topicCallsBefore = provider.topicCalls.size

            vm.retryRailGroup(DiscoverRailGroup.Gutendex)
            advanceUntilIdle()

            // Only the failed rail is retried; the working sibling is not refetched.
            assertEquals(topicCallsBefore + 1, provider.topicCalls.size)
            assertEquals(siblingCallsBefore, provider.topicCalls.count { it == terms[1] })
            assertTrue(vm.uiState.value.rails[1] is DiscoverRailState.Loaded)
            // Still failing, so no group notice exists for a two-rail group.
            assertTrue(
                vm.uiState.value.visibleRailGroupNotices
                    .isEmpty(),
            )
            // The library rule still holds: the failed rail keeps its own error.
            assertTrue(vm.uiState.value.rails[0] is DiscoverRailState.Error)
        }

    @Test
    fun summaryNoticeDisappearsWhenTheWholeGroupRecovers() =
        runTest {
            val terms = todayTerms()
            // Both author rails are down from the start: the whole group failed.
            val provider =
                FakeGroupRailProvider(failingSources = setOf(BUILTIN_GUTENDEX)).apply {
                    failingPopular = true
                }
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            // Every rail of the source failed, so exactly ONE notice names it.
            assertEquals(listOf(DiscoverRailGroup.Gutendex), vm.uiState.value.visibleRailGroupNotices)
            assertTrue(
                vm.uiState.value.rails
                    .take(2)
                    .all { it is DiscoverRailState.Error },
            )

            // The upstream recovers; retrying the group must clear the notice.
            provider.failingSources = emptySet()
            provider.resultsOverride = { term -> topicPage(term) }
            vm.retryRailGroup(DiscoverRailGroup.Gutendex)
            advanceUntilIdle()

            // The group succeeded again, so the notice is gone…
            assertTrue(
                vm.uiState.value.visibleRailGroupNotices
                    .isEmpty(),
            )
            assertTrue(
                vm.uiState.value.failedRailGroups
                    .isEmpty(),
            )
            assertTrue(
                vm.uiState.value.rails
                    .take(2)
                    .all { it is DiscoverRailState.Loaded },
            )
        }

    @Test
    fun aFailedGroupShowsItsNoticeEvenBeforeAnythingIsDismissed() =
        runTest {
            // Regression: `dismissedRailGroups.containsAll(failedRailGroups)` is
            // vacuously true while nothing was dismissed, which hid every notice.
            val provider =
                FakeGroupRailProvider(failingSources = setOf(BUILTIN_GUTENDEX)).apply {
                    failingPopular = true
                }
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            assertTrue(
                vm.uiState.value.dismissedRailGroups
                    .isEmpty(),
            )
            assertEquals(listOf(DiscoverRailGroup.Gutendex), vm.uiState.value.failedRailGroups)
            assertEquals(listOf(DiscoverRailGroup.Gutendex), vm.uiState.value.visibleRailGroupNotices)
        }

    @Test
    fun dismissingANoticeHidesItForTheSession() =
        runTest {
            val provider =
                FakeGroupRailProvider(failingSources = setOf(BUILTIN_GUTENDEX)).apply {
                    failingPopular = true
                }
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()
            assertEquals(listOf(DiscoverRailGroup.Gutendex), vm.uiState.value.visibleRailGroupNotices)

            vm.dismissRailGroup(DiscoverRailGroup.Gutendex)

            assertTrue(
                vm.uiState.value.visibleRailGroupNotices
                    .isEmpty(),
            )
            // The failure is still real: dismissal hides the notice, not the state.
            assertEquals(listOf(DiscoverRailGroup.Gutendex), vm.uiState.value.failedRailGroups)
        }

    /** Rail provider where the selected sources fail; the rest answer normally. */
    private class FakeGroupRailProvider(
        var failingSources: Set<String> = emptySet(),
        private val resultsFor: (String) -> PagedResult = { term -> PagedResult(emptyList(), null, 0) },
    ) : CatalogProvider {
        val searchSourceCalls = mutableListOf<Pair<String, String>>()
        val featuredCalls = mutableListOf<CatalogFeaturedSort>()

        /** Terms that reached the `?topic=` door, in call order. */
        val topicCalls = mutableListOf<String>()

        /** Non-null while set: every rail request blocks on it (in-flight rails). */
        var gate: CompletableDeferred<Unit>? = null

        /** Fails the popular shelf too, so the whole Gutendex source is down. */
        var failingPopular: Boolean = false

        /**
         * Late-binding override for the Google Books rails, so a test can flip a
         * source from failing to answering without rebuilding the ViewModel.
         */
        var resultsOverride: ((String) -> PagedResult)? = null

        override suspend fun search(
            query: String,
            page: Int,
        ): PagedResult = PagedResult(emptyList(), null, 0)

        override suspend fun featured(
            sort: CatalogFeaturedSort,
            page: Int,
        ): PagedResult {
            featuredCalls.add(sort)
            gate?.await()
            if (failingPopular) {
                throw CatalogException(CatalogErrorCode.UPSTREAM_ERROR, "injected")
            }
            return PagedResult(
                listOf(groupBook("gutendex:1", BUILTIN_GUTENDEX, "Popular")),
                null,
                1,
            )
        }

        override fun supportsFeatured(): Boolean = true

        /** The topic shelves' ONLY door: a `?topic=` request, never a search. */
        override suspend fun topic(
            term: String,
            page: Int,
        ): PagedResult {
            topicCalls.add(term)
            gate?.await()
            if (BUILTIN_GUTENDEX in failingSources) {
                throw CatalogException(CatalogErrorCode.RATE_LIMITED, "upstream 429")
            }
            return resultsOverride?.invoke(term) ?: resultsFor(term)
        }

        override fun supportsTopic(): Boolean = true

        override suspend fun getDetails(id: String): CatalogBook = throw CatalogException(CatalogErrorCode.NOT_FOUND, "unused")

        override fun resolveDownloadUrl(
            formats: Map<String, String>,
            preferEpub: Boolean,
        ): String = "n/a"

        override fun listSources(): List<CatalogSourceInfo> = emptyList()

        override suspend fun searchSource(
            sourceId: String,
            query: String,
            page: Int,
        ): PagedResult {
            searchSourceCalls.add(sourceId to query)
            gate?.await()
            if (sourceId in failingSources) {
                throw CatalogException(CatalogErrorCode.RATE_LIMITED, "upstream 429")
            }
            return when (sourceId) {
                BUILTIN_GUTENDEX -> resultsOverride?.invoke(query) ?: resultsFor(query)
                BUILTIN_STANDARD_EBOOKS ->
                    PagedResult(
                        listOf(groupBook("se:1", BUILTIN_STANDARD_EBOOKS, "Suspense")),
                        null,
                        1,
                    )
                else -> PagedResult(emptyList(), null, 0)
            }
        }

        /** Rail card with a cover (coverless cards now render as text cards). */
        private fun groupBook(
            id: String,
            provider: String,
            title: String,
        ) = CatalogBook(
            id = id,
            provider = provider,
            title = title,
            authors = listOf("Some Author"),
            coverUrl = "https://covers.example/$id.jpg",
            languages = listOf("en"),
            subjects = emptyList(),
            downloadUrl = null,
        )
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
            coEvery { useCase.invoke(any(), any()) } returns
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
            coEvery { useCase.invoke(any(), any()) } returns
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
            coEvery { useCase.invoke(any(), any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Failure(CatalogErrorCode.NETWORK_ERROR),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()

            assertTrue(vm.uiState.value.download is DownloadImportState.Failure)

            vm.startDownload()
            coVerify(exactly = 2) { useCase.invoke(any(), any()) }
        }

    // ── U3-2 funnel: download start/complete/fail counters (egress runCatching-safe; assert via UI state) ──

    @Test
    fun downloadSuccess_emitsFunnelStartAndComplete() =
        runTest {
            SentryMetrics.clearForTest()
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any(), any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Downloading(512L, 2048L),
                    DownloadImportState.Success(importedBook()),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()
            assertTrue(vm.uiState.value.download is DownloadImportState.Success)
            coVerify(exactly = 1) { useCase.invoke(any(), any()) }
            SentryMetrics.clearForTest()
        }

    @Test
    fun downloadFailure_emitsFunnelFailWithCode() =
        runTest {
            SentryMetrics.clearForTest()
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any(), any()) } returns
                flowOf(
                    DownloadImportState.Idle,
                    DownloadImportState.Failure(CatalogErrorCode.NETWORK_ERROR),
                )
            val vm = DiscoverViewModel(FakeCatalogProvider(), downloadAndImportBookUseCase = useCase)
            vm.openDetail("gutendex:1342")
            vm.startDownload()
            assertTrue(vm.uiState.value.download is DownloadImportState.Failure)
            coVerify(exactly = 1) { useCase.invoke(any(), any()) }
            SentryMetrics.clearForTest()
        }

    @Test
    fun downloadFailureNullError_emitsProviderOnlyFail() =
        runTest {
            SentryMetrics.clearForTest()
            val useCase = mockk<DownloadAndImportBookUseCase>()
            coEvery { useCase.invoke(any(), any()) } returns flowOf(DownloadImportState.Failure(null))
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
            coEvery { useCase.invoke(any(), any()) } returns
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

        /** Topic door result; the topic shelves never travel through searchSource. */
        var topicResult: (String) -> PagedResult = { PagedResult(emptyList(), null, 0) }
        val searchSourceCalls = mutableListOf<Pair<String, String>>()
        val topicCalls = mutableListOf<String>()

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

        override suspend fun topic(
            term: String,
            page: Int,
        ): PagedResult {
            topicCalls.add(term)
            return topicResult(term)
        }

        override fun supportsTopic(): Boolean = true

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
        coverUrl: String? = null,
    ) = CatalogBook(
        id = id,
        provider = provider,
        title = title,
        authors = listOf("Author A"),
        coverUrl = coverUrl,
        languages = listOf("en"),
        subjects = emptyList(),
        downloadUrl = null,
    )

    /** A rail card needs a cover: rails drop coverless books, search keeps them. */
    private fun railCatalogBook(
        id: String,
        provider: String,
        title: String,
    ) = catalogBook(id, provider, title, coverUrl = "https://covers.example/$id.jpg")

    private val addonSourceId = "addon:0123456789abcdef"

    /** Rail provider whose only source (and whose only searchable page) is an addon. */
    private interface AddonRailProvider : CatalogProvider {
        var sources: List<CatalogSourceInfo>
    }

    private fun addonRailProvider(addonPage: (String) -> PagedResult): AddonRailProvider =
        object : AddonRailProvider {
            override var sources: List<CatalogSourceInfo> =
                listOf(CatalogSourceInfo(addonSourceId, "My Addon", CatalogSourceKind.ADDON))

            override suspend fun search(
                query: String,
                page: Int,
            ): PagedResult = PagedResult(emptyList(), null, 0)

            override suspend fun getDetails(id: String): CatalogBook = throw CatalogException(CatalogErrorCode.NOT_FOUND, "unused")

            override fun resolveDownloadUrl(
                formats: Map<String, String>,
                preferEpub: Boolean,
            ): String = "n/a"

            override fun listSources(): List<CatalogSourceInfo> = sources

            /** Topic rails ask this provider too; it has no topic, so they fail closed. */
            override fun supportsTopic(): Boolean = false

            override suspend fun searchSource(
                sourceId: String,
                query: String,
                page: Int,
            ): PagedResult = addonPage(sourceId)
        }

    @Test
    fun sourceChipsComeFromListSourcesWithAllAsDefault() =
        runTest {
            val provider =
                FakeSourceCatalogProvider().apply {
                    sources =
                        listOf(
                            CatalogSourceInfo(BUILTIN_GUTENDEX, "Gutenberg", CatalogSourceKind.BUILTIN),
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
            val emptyVm = DiscoverViewModel(addonRailProvider { PagedResult(emptyList(), null, 0) })
            advanceUntilIdle()
            // Four plan rails (two topics + popular + Standard Ebooks) + the addon rail.
            assertEquals(5, emptyVm.uiState.value.rails.size)
            // The empty ADDON shelf collapses to Hidden, never to a section.
            assertEquals(DiscoverRailState.Hidden, emptyVm.uiState.value.rails[4])

            val errorVm =
                DiscoverViewModel(
                    addonRailProvider { throw CatalogException(CatalogErrorCode.UPSTREAM_ERROR, "injected") },
                )
            advanceUntilIdle()
            // The ADDON rail still hides itself: an addon must never crowd the
            // IDLE shell with an empty or placeholder section.
            assertTrue(
                errorVm.uiState.value.rails
                    .drop(4)
                    .all { it is DiscoverRailState.Hidden },
            )
            // A provider with no topic capability answers the topic rails empty, so
            // they hide instead of failing — an unsupported door is not an outage.
            assertTrue(
                errorVm.uiState.value.rails
                    .take(2)
                    .all { it is DiscoverRailState.Hidden },
            )
            assertTrue(
                errorVm.uiState.value.rails.none {
                    it is DiscoverRailState.Loaded && it.sourceId == addonSourceId
                },
            )
        }

    @Test
    fun addonRailLoadsFromSearchSourceAndDisappearsOnUninstall() =
        runTest {
            var listener: ((Int) -> Unit)? = null
            val provider =
                addonRailProvider {
                    PagedResult(listOf(railCatalogBook("a:1", addonSourceId, "Addon Book")), null, 1)
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

    // ── U1: source filter narrows the IDLE rails ─────────────────────────

    @Test
    fun sourceFilterNarrowsIdleRailsAndHidesFullyFilteredOnes() =
        runTest {
            val provider =
                FakeSourceCatalogProvider().apply {
                    sources = listOf(CatalogSourceInfo(addonSourceId, "My Addon", CatalogSourceKind.ADDON))
                    // Topic shelves ask the `?topic=` door with the day's terms.
                    // The addon rail queries its own source id. Featured and
                    // Standard Ebooks answer empty, so the plan contributes two
                    // more Hidden rails.
                    topicResult = { term ->
                        PagedResult(
                            listOf(railCatalogBook("g:${term.hashCode()}", BUILTIN_GUTENDEX, "By $term")),
                            null,
                            1,
                        )
                    }
                    sourceResult = { sourceId ->
                        when (sourceId) {
                            addonSourceId ->
                                PagedResult(listOf(railCatalogBook("a:1", addonSourceId, "Addon Book")), null, 1)
                            else -> PagedResult(emptyList(), null, 0)
                        }
                    }
                }
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            // Two topic shelves, the popular + Standard Ebooks rails (both empty
            // here), then the addon rail: the plan length is fixed.
            val rails = vm.uiState.value.rails
            assertEquals(5, rails.size)
            assertTrue(rails.take(2).all { it is DiscoverRailState.Loaded })
            assertEquals(DiscoverRailState.Hidden, rails[2])
            assertEquals(DiscoverRailState.Hidden, rails[3])
            // "All" leaves every rail exactly as loaded.
            assertEquals(vm.uiState.value.rails, vm.uiState.value.visibleRails)
            val addonRailWidth = (rails[4] as DiscoverRailState.Loaded).books.size
            assertEquals(1, addonRailWidth)

            vm.setSourceFilter(DiscoverSourceFilter.Source(addonSourceId))

            val narrowed = vm.uiState.value.visibleRails
            assertEquals(5, narrowed.size)
            // The addon rail keeps only its own books; the author shelves are
            // Google Books sourced, so a non-Google-Books selection hides them.
            assertTrue(narrowed.take(4).all { it is DiscoverRailState.Hidden })
            val addonRail = narrowed[4] as DiscoverRailState.Loaded
            assertEquals(listOf(addonSourceId), addonRail.books.map { it.provider })

            // Narrowing is presentation-only: the loaded state keeps every book.
            val sourceRails = vm.uiState.value.rails
            assertEquals(addonRailWidth, (sourceRails[4] as DiscoverRailState.Loaded).books.size)
            assertEquals(vm.uiState.value.rails, sourceRails)
        }

    @Test
    fun aRailThatFiltersToZeroBooksBecomesHidden() =
        runTest {
            // Two addon rails, each carrying a book from its own addon source:
            // selecting one narrows that rail and hides the other.
            val otherSource = CatalogSourceInfo("addon:ffffffffffffffff", "Other Addon", CatalogSourceKind.ADDON)
            val provider =
                FakeSourceCatalogProvider().apply {
                    sources =
                        listOf(
                            CatalogSourceInfo(addonSourceId, "My Addon", CatalogSourceKind.ADDON),
                            otherSource,
                        )
                    sourceResult = { sourceId ->
                        when (sourceId) {
                            addonSourceId -> PagedResult(listOf(railCatalogBook("a:1", addonSourceId, "Mine")), null, 1)
                            otherSource.sourceId -> PagedResult(listOf(railCatalogBook("a:2", otherSource.sourceId, "Theirs")), null, 1)
                            // Topic shelves, featured and Standard Ebooks fail closed to Hidden.
                            else -> PagedResult(emptyList(), null, 0)
                        }
                    }
                }

            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            val loaded = vm.uiState.value.visibleRails
            // The four plan rails collapse to Hidden (no Google Books / featured /
            // Standard Ebooks page), then the two addon rails.
            assertEquals(6, loaded.size)
            assertTrue(loaded[4] is DiscoverRailState.Loaded)
            assertTrue(loaded[5] is DiscoverRailState.Loaded)

            vm.setSourceFilter(DiscoverSourceFilter.Source(addonSourceId))

            val rails = vm.uiState.value.visibleRails
            assertEquals(listOf(addonSourceId), (rails[4] as DiscoverRailState.Loaded).books.map { it.provider })
            assertEquals(DiscoverRailState.Hidden, rails[5])
        }

    @Test
    fun selectingASourceWithNoRailsHidesEveryRailAndAllRestoresThem() =
        runTest {
            val provider =
                FakeSourceCatalogProvider().apply {
                    sources = listOf(CatalogSourceInfo(addonSourceId, "My Addon", CatalogSourceKind.ADDON))
                    sourceResult = {
                        PagedResult(listOf(railCatalogBook("a:1", addonSourceId, "Addon Book")), null, 1)
                    }
                }
            val vm = DiscoverViewModel(provider)
            advanceUntilIdle()

            vm.setSourceFilter(DiscoverSourceFilter.Source(BUILTIN_GUTENDEX))

            assertTrue(
                vm.uiState.value.visibleRails
                    .all { it is DiscoverRailState.Hidden },
            )

            vm.setSourceFilter(DiscoverSourceFilter.AllSources)

            assertTrue(
                vm.uiState.value.visibleRails
                    .any { it is DiscoverRailState.Loaded },
            )
            assertEquals(vm.uiState.value.rails, vm.uiState.value.visibleRails)
        }

    @Test
    fun loadingRailsStayLoadingUnderAnActiveSourceFilter() =
        runTest {
            val provider = GatedFeaturedProvider()
            val vm = DiscoverViewModel(provider)

            vm.setSourceFilter(DiscoverSourceFilter.Source(BUILTIN_GUTENDEX))

            assertTrue(
                vm.uiState.value.visibleRails
                    .all { it is DiscoverRailState.Loading },
            )
            provider.gate.complete(Unit)
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


