package com.nexo.testutil

import com.nexo.domain.model.Bookmark
import com.nexo.domain.model.Highlight
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.ReaderRepository
import com.nexo.domain.repository.ReadingStatsData
import com.nexo.domain.repository.ReadingStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared fakes for reader ViewModel slice tests (SDD reader-facade-split).
 *
 * One fake serves every slice test (T1 harness through T8 closure) so new
 * slice tests add assertions, not fixture copies. Pass a custom
 * [highlightsFlow] when the test drives highlight emissions (ordering pins).
 *
 * The write path records what it was last called with ([lastUpserted],
 * [lastUserId]) so account-scoping and progress assertions need no bespoke
 * fake.
 */
class FakeReaderRepository(
    private val highlightsFlow: MutableStateFlow<List<Highlight>> = MutableStateFlow(emptyList()),
) : ReaderRepository {
    private val progressFlow = MutableStateFlow<ReadingProgress?>(null)

    /** Last progress handed to [upsertProgress], or `null` when never written. */
    var lastUpserted: ReadingProgress? = null
        private set

    /** Account id the last [upsertProgress] was attributed to. */
    var lastUserId: String? = null
        private set

    override fun observeProgress(
        bookId: String,
        userId: String,
    ): Flow<ReadingProgress?> = progressFlow

    override suspend fun upsertProgress(
        progress: ReadingProgress,
        userId: String,
    ) {
        lastUpserted = progress
        lastUserId = userId
        progressFlow.value = progress
    }

    override suspend fun updateBookReadingState(
        bookId: String,
        userId: String,
        progressPercent: Float,
        updatedAt: Long,
    ) = Unit

    override fun observeAllHighlights(userId: String): Flow<List<Highlight>> = highlightsFlow

    override fun observeHighlights(
        bookId: String,
        userId: String,
    ): Flow<List<Highlight>> = highlightsFlow

    override suspend fun upsertHighlight(
        highlight: Highlight,
        userId: String,
    ) = Unit

    override fun observeAllBookmarks(userId: String): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

    override fun observeBookmarks(
        bookId: String,
        userId: String,
    ): Flow<List<Bookmark>> = MutableStateFlow(emptyList())

    override suspend fun upsertBookmark(
        bookmark: Bookmark,
        userId: String,
    ) = Unit

    override suspend fun getProgressForBook(
        bookId: String,
        userId: String,
    ): ReadingProgress? = null

    override suspend fun getHighlightsForBook(
        bookId: String,
        userId: String,
    ): List<Highlight> = emptyList()

    override suspend fun getBookmarksForBook(
        bookId: String,
        userId: String,
    ): List<Bookmark> = emptyList()
}

class FakeReadingStatsRepository : ReadingStatsRepository {
    override fun observeStats(
        bookId: String,
        userId: String,
    ): Flow<ReadingStatsData?> = MutableStateFlow(null)

    override fun observeTotalTime(userId: String): Flow<Long> = MutableStateFlow(0L)

    override suspend fun updateReadingTime(
        bookId: String,
        userId: String,
        additionalMinutes: Long,
    ) = Unit

    override suspend fun deleteStats(
        bookId: String,
        userId: String,
    ) = Unit

    override fun observeBookStats(userId: String): Flow<List<ReadingStatsData>> = MutableStateFlow(emptyList())

    override suspend fun getDailyActivity(userId: String?): List<com.nexo.domain.model.DailyReadingActivity> = emptyList()
}
