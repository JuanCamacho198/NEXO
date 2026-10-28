package com.nexo.presentation.viewmodel

import com.nexo.domain.model.AuthSession
import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingStats
import com.nexo.domain.repository.HomeRepository
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.usecase.GetBookProgressUseCase
import com.nexo.domain.usecase.GetStatisticsUseCase
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Regressions for the "Home never leaves the empty-library state" defect.
 *
 * The defect was a one-shot read of the active account id. `requireUserId()` is a
 * plain function, so the five user-scoped `combine` sources captured `""` at
 * ViewModel construction — before `SessionSyncEffect` publishes the restored
 * session — and the `observeBooks("")` subscription was never re-created. Since a
 * persisted row always carries a real owner id (decision D5), that query matched
 * nothing forever: `allBooks` stayed empty, `isLibraryEmpty` stayed `true`, and
 * Home kept rendering the no-books layout after the account resolved and after a
 * book was imported.
 *
 * These tests drive the [ActiveUserProvider] explicitly — the same way
 * `SessionSyncEffect` does in production — because the provider is the single
 * source of truth the queries must follow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelActiveUserReactivityTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val provider = ActiveUserProvider()

    /** Every account id `observeBooks` was asked for, in subscription order. */
    private val booksRequestedFor = mutableListOf<String?>()

    private val allBooks = MutableStateFlow<List<Book>>(emptyList())

    private val repository: HomeRepository =
        mockk(relaxed = true) {
            every { observeBooks(any()) } answers
                {
                    val userId = firstArg<String>()
                    booksRequestedFor += userId
                    // A signed-out caller sees nothing: no persisted row carries
                    // an empty owner (decision D5).
                    if (userId.isBlank()) MutableStateFlow(emptyList()) else allBooks
                }
            every { observeCurrentBooks(any()) } answers
                {
                    val userId = firstArg<String>()
                    if (userId.isBlank()) MutableStateFlow(emptyList()) else allBooks
                }
            every { observeRecentBooks(any(), any()) } answers
                {
                    val userId = firstArg<String>()
                    if (userId.isBlank()) MutableStateFlow(emptyList()) else allBooks
                }
            every { observeDailyStats(any(), any()) } returns MutableStateFlow(ReadingStats())
        }

    private fun createViewModel(): HomeViewModel {
        val statsUseCase =
            GetStatisticsUseCase(
                readingStatsRepository = FakeReadingStatsRepository(),
                homeRepository = repository,
                dailyGoalProvider = { 30 },
                zoneId = java.time.ZoneId.of("UTC"),
            )
        return HomeViewModel(
            homeRepository = repository,
            getStatisticsUseCase = statsUseCase,
            dailyGoalProvider = { 30 },
            readerRepository = mockk<ReaderRepository>(relaxed = true),
            getBookProgressUseCase = mockk<GetBookProgressUseCase>(relaxed = true),
            activeUser = provider,
        )
    }

    @Test
    fun libraryStaysLoadingUntilTheAccountResolvesThenRendersItsBooks() =
        runTest {
            val viewModel = createViewModel()
            runCurrent()

            // Pre-session: no owner yet, so the library is unknown. The skeleton
            // owns this window and the empty layout must NOT show.
            val preSession = viewModel.uiState.value
            assertTrue("Pre-session Home is loading, not resolved", preSession.isLoading)
            assertFalse(
                "An unresolved owner must not read as an empty library",
                preSession.isLibraryEmpty,
            )
            // `GetStatisticsUseCase` legitimately queries the "legacy rows" scope
            // (empty owner) on its own, so the guard here is that Home never asks
            // for a *real* account before one is known.
            assertTrue(
                "The unresolved home must not subscribe an owner-scoped shelf query",
                booksRequestedFor.none { it == TEST_USER },
            )

            // SessionSyncEffect publishes the account.
            provider.set(TEST_USER)
            runCurrent()
            assertTrue("The shelf query carries the resolved owner", booksRequestedFor.contains(TEST_USER))

            // The account's shelf has a book.
            allBooks.value = listOf(book("book-1"))
            advanceUntilIdle()

            val resolved = viewModel.uiState.value
            assertFalse("The owner is known, so Home is no longer loading", resolved.isLoading)
            assertEquals(listOf("book-1"), resolved.allBooks.map { it.id })
            assertFalse(
                "A populated library must leave the empty-library layout",
                resolved.isLibraryEmpty,
            )
        }

    @Test
    fun aBookImportedAfterTheAccountResolvesFlipsIsLibraryEmptyToFalse() =
        runTest {
            val viewModel = createViewModel()
            runCurrent()

            provider.set(TEST_USER)
            advanceUntilIdle()

            // The account resolved with an empty shelf — the genuine first-session
            // state. This is the one case where the empty layout is correct.
            assertFalse(viewModel.uiState.value.isLoading)
            assertTrue(viewModel.uiState.value.isLibraryEmpty)

            // The user imports a book: Room emits on the subscription that is
            // already scoped to the real id.
            allBooks.value = listOf(book("imported"))
            advanceUntilIdle()

            assertFalse(
                "An import after the session resolves must leave the empty state",
                viewModel.uiState.value.isLibraryEmpty,
            )
            assertEquals(listOf("imported"), viewModel.uiState.value.allBooks.map { it.id })
        }

    @Test
    fun signingOutDropsThePreviousAccountsRowsFromTheState() =
        runTest {
            val viewModel = createViewModel()
            runCurrent()

            provider.set(TEST_USER)
            allBooks.value = listOf(book("mine"))
            advanceUntilIdle()
            assertEquals(listOf("mine"), viewModel.uiState.value.allBooks.map { it.id })

            // Sign-out clears the owner, so the previous account's rows must not
            // survive in the state. The last resolved emission is already "no
            // owner", so the state does not advance — which is why `isLoading`
            // stays false here, exactly as it does on first launch before any
            // account has ever been seen.
            provider.clear()
            advanceUntilIdle()

            val signedOut = viewModel.uiState.value
            assertTrue("The previous account's rows must not persist", signedOut.allBooks.isEmpty())
            assertFalse(
                "An empty library is not claimable without a known owner",
                signedOut.isLibraryEmpty,
            )
        }

    @Test
    fun switchingAccountsReSubscribesTheQueriesToTheNewOwner() =
        runTest {
            val viewModel = createViewModel()
            runCurrent()

            provider.set(TEST_USER)
            runCurrent()
            booksRequestedFor.clear()

            provider.set(OTHER_USER)
            runCurrent()

            assertTrue(
                "A changed owner must re-subscribe",
                booksRequestedFor.contains(OTHER_USER),
            )
            assertTrue(
                "The new query carries the new owner, never the previous one",
                booksRequestedFor.all { it == OTHER_USER },
            )
        }

    @Test
    fun setActiveSessionDoesNotOwnTheQueryScope() =
        runTest {
            val viewModel = createViewModel()
            runCurrent()
            booksRequestedFor.clear()

            // The legacy path: a session handed to the ViewModel directly must not
            // re-scope the queries. ActiveUserProvider is the single writer.
            viewModel.setActiveSession(
                AuthSession(userId = TEST_USER, email = "u@example.com", displayName = "U"),
            )
            runCurrent()

            assertTrue(
                "Only ActiveUserProvider may scope the library queries",
                booksRequestedFor.isEmpty(),
            )
            assertEquals("U", viewModel.uiState.value.userName)
        }
    private fun book(id: String) =
        Book(
            id = id,
            title = "Book $id",
            author = "Author",
            coverPath = null,
            filePath = "/path/$id.epub",
            format = "epub",
            updatedAtEpochMillis = 1_000L,
        )

    private class FakeReadingStatsRepository : com.nexo.domain.repository.ReadingStatsRepository {
        override fun observeStats(
            bookId: String,
            userId: String,
        ): kotlinx.coroutines.flow.Flow<com.nexo.domain.repository.ReadingStatsData?> = MutableStateFlow(null)

        override fun observeTotalTime(userId: String): kotlinx.coroutines.flow.Flow<Long> = MutableStateFlow(0L)

        override fun observeBookStats(userId: String): kotlinx.coroutines.flow.Flow<List<com.nexo.domain.repository.ReadingStatsData>> = MutableStateFlow(emptyList())

        override suspend fun getDailyActivity(userId: String?): List<com.nexo.domain.model.DailyReadingActivity> = emptyList()

        override suspend fun updateReadingTime(
            bookId: String,
            userId: String,
            additionalMinutes: Long,
        ) = Unit

        override suspend fun deleteStats(
            bookId: String,
            userId: String,
        ) = Unit
    }

    private companion object {
        const val TEST_USER = "user-a"
        const val OTHER_USER = "user-b"
    }
}
