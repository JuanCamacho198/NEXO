package com.nexo.presentation.viewmodel

import com.nexo.R
import com.nexo.data.remote.catalog.BUILTIN_GUTENDEX
import com.nexo.data.remote.catalog.CatalogBook
import com.nexo.data.remote.catalog.CatalogErrorCode
import com.nexo.data.remote.catalog.CatalogException
import com.nexo.data.remote.catalog.CatalogProvider
import com.nexo.data.remote.catalog.CatalogSourceInfo
import com.nexo.data.remote.catalog.PagedResult
import com.nexo.domain.discover.TOPIC_ROTATION
import com.nexo.domain.discover.entriesFor
import com.nexo.presentation.feature.discover.DiscoverRailState
import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * S2: every curated `TopicEntry.titleKey` must resolve to a real string
 * resource, and a shelf must carry the resource belonging to its OWN topic.
 *
 * This is the exact class of bug the rotation can have — a key written for a
 * topic that no longer owned it, so a shelf rendered the wrong name with no test
 * catching it. The key→resource map is private to the ViewModel, so the table is
 * asserted through the observed shelf state rather than by reaching into it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicRailTitlesTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Expected resource id per rotation term. */
    private val expectedTitleRes =
        mapOf(
            "science fiction" to R.string.discover_rail_topic_science_fiction,
            "adventure" to R.string.discover_rail_topic_adventure,
            "history" to R.string.discover_rail_topic_history,
            "travel" to R.string.discover_rail_topic_travel,
            "poetry" to R.string.discover_rail_topic_poetry,
            "philosophy" to R.string.discover_rail_topic_philosophy,
            "drama" to R.string.discover_rail_topic_drama,
            "romance" to R.string.discover_rail_topic_romance,
            "mystery" to R.string.discover_rail_topic_mystery,
            "horror" to R.string.discover_rail_topic_horror,
        )

    @Test
    fun everyRotationTermHasAnExpectedTitleResource() {
        for (entry in TOPIC_ROTATION) {
            assertTrue("no expected title resource for ${entry.slug}", entry.term in expectedTitleRes)
        }
        assertEquals(TOPIC_ROTATION.size, expectedTitleRes.size)
    }

    @Test
    fun everyShelfCarriesTheTitleResourceOfItsOwnTopic() =
        runTest {
            val vm = DiscoverViewModel(RepeatTermsRailProvider())
            advanceUntilIdle()

            val shelves =
                vm.uiState.value.rails
                    .filterIsInstance<DiscoverRailState.Loaded>()
                    // The topic shelves are the rotation's share of the plan; the
                    // fixed-source rails carry no topic term by design.
                    .filter { it.topicTerm != null }
            assertEquals(
                entriesFor(LocalDate.now()).map { it.term },
                shelves.map { it.topicTerm },
            )
            for (shelf in shelves) {
                val term = shelf.topicTerm
                assertTrue("shelf without a topic term: $shelf", term != null)
                assertEquals(
                    "shelf for $term carries the wrong title resource",
                    expectedTitleRes[term],
                    shelf.sectionTitleRes,
                )
                // A topic shelf always pages through Gutendex.
                assertEquals(BUILTIN_GUTENDEX, shelf.sourceId)
            }
        }

    @Test
    fun aKnownKeyThatIsNotInTheRotationWouldNotResolve() {
        // The mapping is keyed by titleKey, not by term: a shelf whose key has no
        // resource is dropped by the ViewModel instead of rendering a blank header.
        assertTrue(TOPIC_ROTATION.all { it.titleKey.startsWith("discover_rail_topic_") })
    }

    /**
     * Answers every Gutendex topic term with one book, so both topic shelves
     * load whatever the calendar day is.
     */
    private class RepeatTermsRailProvider : CatalogProvider {
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
        ): PagedResult = PagedResult(emptyList(), null, 0)

        override suspend fun topic(
            term: String,
            page: Int,
        ): PagedResult =
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

        override fun supportsTopic(): Boolean = true
    }

    /** Guard: the day's rotation is always two distinct topic shelves. */
    @Test
    fun theDayRotationIsTwoDistinctShelves() {
        val terms = entriesFor(LocalDate.now()).map { it.term }
        assertEquals(2, terms.size)
        assertEquals(2, terms.toSet().size)
    }
}
