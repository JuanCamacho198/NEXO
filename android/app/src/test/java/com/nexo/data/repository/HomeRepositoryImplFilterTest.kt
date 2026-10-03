package com.nexo.data.repository

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.domain.model.Book
import com.nexo.domain.model.BookStatus
import com.nexo.domain.model.ReadingState
import com.nexo.domain.model.isActiveReadingCandidate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the WS1 active-reading filter in [HomeRepositoryImpl.observeCurrentBooks]
 * against the `home-continue-reading` spec scenarios (CR1–CR6) and the pure
 * [isActiveReadingCandidate] predicate truth table. The DAOs are mocked, so the
 * seeded rows are exactly what `observeReadingBooks()` already returns; the filter
 * under test is what prunes the stale/duplicate signals.
 */
class HomeRepositoryImplFilterTest {
    // CR1 — explicit `status='completed'` wins over a stale `reading_state='reading'`.
    @Test
    fun explicitCompletedStatus_excluded() =
        runBlocking {
            val books =
                currentBooksFor(
                    listOf(book("completed", status = BookStatus.COMPLETED, cachedProgress = 40f)),
                )

            assertTrue("completed book must not appear in Continue Reading", books.isEmpty())
        }

    // CR2 — canonical `reading_progress.percentage == 100` with null status.
    @Test
    fun canonicalProgress100WithNullStatus_excluded() =
        runBlocking {
            val books =
                currentBooksFor(
                    books = listOf(book("finished-canonically", status = null, cachedProgress = 40f)),
                    progresses = listOf(canonical("finished-canonically", percentage = 100f)),
                )

            assertTrue("canonical 100% must exclude the book", books.isEmpty())
        }

    // CR3 — explicit `status='plan_to_read'`.
    @Test
    fun planToReadStatus_excluded() =
        runBlocking {
            val books =
                currentBooksFor(
                    listOf(book("planned", status = BookStatus.PLAN_TO_READ, cachedProgress = 30f)),
                )

            assertTrue("plan-to-read book must not appear in Continue Reading", books.isEmpty())
        }

    // CR4 — explicit `status='reading'`, canonical progress < 100.
    @Test
    fun pureReadingStatus_included() =
        runBlocking {
            val books =
                currentBooksFor(
                    listOf(book("reading", status = BookStatus.READING, cachedProgress = 40f)),
                )

            assertEquals(listOf("reading"), books.map { it.id })
        }

    // CR4 — null status, canonical progress < 100, no completed signal.
    @Test
    fun nullStatusWithCanonicalProgress_included() =
        runBlocking {
            val books =
                currentBooksFor(
                    books = listOf(book("in-progress", status = null, cachedProgress = 15f)),
                    progresses = listOf(canonical("in-progress", percentage = 55f)),
                )

            assertEquals(listOf("in-progress"), books.map { it.id })
        }

    // CR5 — explicit `status='plan_to_read'` is authoritative over canonical progress 100.
    @Test
    fun explicitStatusBeatsCanonicalProgress_excluded() =
        runBlocking {
            val books =
                currentBooksFor(
                    books = listOf(book("stale-status", status = BookStatus.PLAN_TO_READ, cachedProgress = 20f)),
                    progresses = listOf(canonical("stale-status", percentage = 100f)),
                )

            assertTrue("explicit status must win over canonical progress", books.isEmpty())
        }

    // CR6 — canonical progress 100 overrides a stale `reading_state='reading'` with null status.
    @Test
    fun canonicalProgressBeatsStaleReadingState_excluded() =
        runBlocking {
            val books =
                currentBooksFor(
                    books = listOf(book("stale-state", status = null, cachedProgress = 10f)),
                    progresses = listOf(canonical("stale-state", percentage = 100f)),
                )

            assertTrue("canonical progress must win over stale reading state", books.isEmpty())
        }

    @Test
    fun mixedShelf_returnsOnlyActiveReadingCandidates() =
        runBlocking {
            val books =
                currentBooksFor(
                    books =
                        listOf(
                            book("completed", status = BookStatus.COMPLETED, cachedProgress = 50f),
                            book("planned", status = BookStatus.PLAN_TO_READ, cachedProgress = 50f),
                            book("canonical-100", status = null, cachedProgress = 50f),
                            book("active", status = BookStatus.READING, cachedProgress = 50f),
                        ),
                    progresses = listOf(canonical("canonical-100", percentage = 100f)),
                )

            assertEquals(listOf("active"), books.map { it.id })
        }

    // Pure predicate truth table — branches the DAO query can never emit.

    @Test
    fun predicate_completedStatus_false() {
        assertFalse(domainBook(status = BookStatus.COMPLETED, progressPercentage = 50f).isActiveReadingCandidate())
    }

    @Test
    fun predicate_planToReadStatus_false() {
        assertFalse(domainBook(status = BookStatus.PLAN_TO_READ, progressPercentage = 50f).isActiveReadingCandidate())
    }

    @Test
    fun predicate_canonicalProgress100_false() {
        assertFalse(domainBook(status = null, progressPercentage = 100f).isActiveReadingCandidate())
    }

    @Test
    fun predicate_completedReadingStateWithoutStatus_false() {
        assertFalse(
            domainBook(status = null, readingState = ReadingState.COMPLETED, progressPercentage = 50f)
                .isActiveReadingCandidate(),
        )
    }

    @Test
    fun predicate_readingStateReading_included() {
        assertTrue(
            domainBook(status = null, readingState = ReadingState.READING, progressPercentage = 25f)
                .isActiveReadingCandidate(),
        )
    }

    @Test
    fun predicate_nullStatusZeroProgressToRead_false() {
        assertFalse(
            domainBook(status = null, readingState = ReadingState.TO_READ, progressPercentage = 0f)
                .isActiveReadingCandidate(),
        )
    }

    @Test
    fun predicate_explicitReadingStatusWithStaleState_included() {
        assertTrue(
            domainBook(status = BookStatus.READING, readingState = ReadingState.TO_READ, progressPercentage = 25f)
                .isActiveReadingCandidate(),
        )
    }

    private suspend fun currentBooksFor(
        books: List<BookEntity>,
        progresses: List<ReadingProgressEntity> = emptyList(),
    ): List<Book> {
        val bookDao = mockk<BookDao>()
        every { bookDao.observeReadingBooks() } returns flowOf(books)
        val readingProgressDao = mockk<ReadingProgressDao>()
        every { readingProgressDao.observeAll() } returns flowOf(progresses)
        return HomeRepositoryImpl(
            bookDao = bookDao,
            readingProgressDao = readingProgressDao,
            readingSessionDao = mockk<ReadingSessionDao>(),
            zoneId = java.time.ZoneId.of("UTC"),
        ).observeCurrentBooks().first()
    }

    private fun book(
        id: String,
        status: String? = null,
        cachedProgress: Float = 0f,
    ): BookEntity =
        BookEntity(
            id = id,
            title = "Book $id",
            author = null,
            coverPath = null,
            filePath = "/tmp/$id.epub",
            format = "epub",
            updatedAtEpochMillis = 1_000L,
            status = status,
            readingState = ReadingState.READING,
            progressPercentage = cachedProgress,
        )

    private fun canonical(
        bookId: String,
        percentage: Float,
    ): ReadingProgressEntity =
        ReadingProgressEntity(
            id = "progress-$bookId",
            bookId = bookId,
            cfiLocation = "epubcfi(/6/2)",
            percentage = percentage,
            updatedAtEpochMillis = 2_000L,
        )

    private fun domainBook(
        status: String? = null,
        readingState: String = ReadingState.READING,
        progressPercentage: Float = 0f,
    ): Book =
        Book(
            id = "book-1",
            title = "Test Book",
            author = null,
            coverPath = null,
            filePath = "/tmp/book-1.epub",
            format = "epub",
            updatedAtEpochMillis = 0L,
            status = status,
            readingState = readingState,
            progressPercentage = progressPercentage,
        )
}
