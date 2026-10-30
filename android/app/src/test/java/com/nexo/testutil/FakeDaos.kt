package com.nexo.testutil

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.BookmarkDao
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.dao.HighlightDao
import com.nexo.data.local.dao.ReadingProgressDao
import com.nexo.data.local.dao.ReadingSessionDao
import com.nexo.data.local.dao.ReadingStatsDao
import com.nexo.data.local.dao.StoredBookPath
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.BookmarkEntity
import com.nexo.data.local.entity.DictionaryWordEntity
import com.nexo.data.local.entity.HighlightEntity
import com.nexo.data.local.entity.ReadingProgressEntity
import com.nexo.data.local.entity.ReadingSessionEntity
import com.nexo.data.local.entity.ReadingStatsEntity
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.local.model.DailyReadingMinutes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The account id every user-scoped fake is keyed to in tests.
 *
 * Rows written without an explicit owner land here, so a test that exercises a
 * single account needs no changes beyond passing [TEST_USER_ID] to the
 * repository/DAO call.
 */
const val TEST_USER_ID: String = "test-user"

/** A second account, for the isolation assertions the account-scoping change requires. */
const val OTHER_USER_ID: String = "other-user"

/**
 * Builds an [com.nexo.domain.session.ActiveUserProvider] already scoped to
 * [userId] so a ViewModel under test reads the account it expects.
 */
fun activeUserProviderFor(userId: String? = TEST_USER_ID): com.nexo.domain.session.ActiveUserProvider =
    com.nexo.domain.session
        .ActiveUserProvider()
        .apply { set(userId) }

/** In-memory [BookDao] scoped by `userId`, mirroring the SQL predicate. */
open class FakeBookDao : BookDao {
    val books = mutableMapOf<String, BookEntity>()

    /** When true, [upsert]/[upsertAll] fail, so error paths can be exercised. */
    var failUpsert = false

    private val state = MutableStateFlow(0)

    private fun rows(userId: String) = books.values.filter { it.userId == userId && it.deletedAtEpochMillis == null }

    override fun observeAllBooks(userId: String): Flow<List<BookEntity>> = state.map { rows(userId).sortedByDescending { it.updatedAtEpochMillis } }

    override fun observeReadingBooks(userId: String): Flow<List<BookEntity>> = state.map { rows(userId).filter { it.readingState == "reading" } }

    override fun observeAllBooksPaged(userId: String) = com.nexo.testutil.FakePagingSource(listOf<BookEntity>())

    override suspend fun upsert(book: BookEntity) {
        if (failUpsert) throw IllegalStateException("db down")
        books[book.id] = book
        state.value++
    }

    override suspend fun upsertAll(books: List<BookEntity>) {
        books.forEach { this.books[it.id] = it }
        state.value++
    }

    override fun observeBookById(
        bookId: String,
        userId: String,
    ): Flow<BookEntity?> = state.map { books[bookId]?.takeIf { it.userId == userId } }

    override suspend fun getBookById(
        bookId: String,
        userId: String,
    ): BookEntity? = books[bookId]?.takeIf { it.userId == userId }

    override suspend fun deleteBook(
        bookId: String,
        userId: String,
        deletedAt: Long,
    ) {
        books[bookId]?.takeIf { it.userId == userId }?.let {
            books[bookId] = it.copy(deletedAtEpochMillis = deletedAt, updatedAtEpochMillis = deletedAt)
            state.value++
        }
    }

    override suspend fun deleteById(
        bookId: String,
        userId: String,
    ) {
        if (books[bookId]?.userId == userId) books.remove(bookId)
        state.value++
    }

    override suspend fun updateRating(
        bookId: String,
        userId: String,
        rating: Int?,
    ) {
        books[bookId]?.takeIf { it.userId == userId }?.let { books[bookId] = it.copy(userRating = rating) }
        state.value++
    }

    override suspend fun updateStatus(
        bookId: String,
        userId: String,
        status: String?,
        updatedAt: Long,
    ) {
        books[bookId]?.takeIf { it.userId == userId }?.let { books[bookId] = it.copy(status = status, updatedAtEpochMillis = updatedAt) }
        state.value++
    }

    override suspend fun startReading(
        bookId: String,
        userId: String,
        updatedAt: Long,
    ) {
        books[bookId]?.takeIf { it.userId == userId }?.let {
            books[bookId] = it.copy(readingState = "reading", startedAtEpochMillis = it.startedAtEpochMillis ?: updatedAt, updatedAtEpochMillis = updatedAt)
        }
        state.value++
    }

    override suspend fun updateReadingProgress(
        bookId: String,
        userId: String,
        progress: Float,
        updatedAt: Long,
    ) {
        books[bookId]?.takeIf { it.userId == userId }?.let {
            books[bookId] =
                it.copy(
                    readingState = if (progress >= 100f) "completed" else "reading",
                    progressUpdatedAtEpochMillis = updatedAt,
                    updatedAtEpochMillis = updatedAt,
                )
        }
        state.value++
    }

    override suspend fun completeReading(
        bookId: String,
        userId: String,
        updatedAt: Long,
    ) {
        books[bookId]?.takeIf { it.userId == userId }?.let {
            books[bookId] = it.copy(readingState = "completed", completedAtEpochMillis = updatedAt, updatedAtEpochMillis = updatedAt)
        }
        state.value++
    }

    override suspend fun updateMetadata(
        bookId: String,
        userId: String,
        title: String,
        author: String?,
        description: String?,
        coverPath: String?,
        genre: String?,
        language: String?,
        publisher: String?,
        tags: String?,
        publishedDate: String?,
        updatedAt: Long,
    ) {
        books[bookId]?.takeIf { it.userId == userId }?.let {
            books[bookId] =
                it.copy(
                    title = title,
                    author = author,
                    description = description,
                    coverPath = coverPath,
                    genre = genre,
                    language = language,
                    publisher = publisher,
                    tags = tags,
                    publishedDate = publishedDate,
                    updatedAtEpochMillis = updatedAt,
                )
        }
        state.value++
    }

    override suspend fun count(userId: String): Int = rows(userId).size

    override suspend fun allStoredPaths(userId: String): List<StoredBookPath> = rows(userId).filter { it.filePath.isNotBlank() }.map { StoredBookPath(it.id, it.filePath) }

    override suspend fun distinctStoredPathOwners(): List<String> =
        books.values
            .filter { it.filePath.isNotBlank() }
            .map { it.userId }
            .distinct()

    override suspend fun updateFilePath(
        bookId: String,
        userId: String,
        filePath: String,
    ) {
        books[bookId]?.takeIf { it.userId == userId }?.let { books[bookId] = it.copy(filePath = filePath) }
        state.value++
    }
}

/** In-memory [ReadingProgressDao] scoped by `userId`. */
open class FakeReadingProgressDao : ReadingProgressDao {
    val rows = mutableMapOf<String, ReadingProgressEntity>()

    private val state = MutableStateFlow(0)

    override fun observeProgressForBook(
        bookId: String,
        userId: String,
    ): Flow<ReadingProgressEntity?> = state.map { rows[bookId]?.takeIf { it.userId == userId } }

    override suspend fun getProgressForBook(
        bookId: String,
        userId: String,
    ): ReadingProgressEntity? = rows[bookId]?.takeIf { it.userId == userId }

    override suspend fun upsert(progress: ReadingProgressEntity) {
        rows[progress.bookId] = progress
        state.value++
    }

    override suspend fun getAll(userId: String): List<ReadingProgressEntity> = rows.values.filter { it.userId == userId }

    override fun observeAll(userId: String): Flow<List<ReadingProgressEntity>> = state.map { rows.values.filter { it.userId == userId } }

    override suspend fun count(userId: String): Int = rows.values.count { it.userId == userId }
}

/** In-memory [ReadingStatsDao] scoped by `userId`. */
open class FakeReadingStatsDao : ReadingStatsDao {
    val rows = mutableMapOf<String, ReadingStatsEntity>()

    private val state = MutableStateFlow(0)

    override fun observeStatsForBook(
        bookId: String,
        userId: String,
    ): Flow<ReadingStatsEntity?> = state.map { rows[bookId]?.takeIf { it.userId == userId } }

    override fun observeAllStats(userId: String): Flow<List<ReadingStatsEntity>> = state.map { rows.values.filter { it.userId == userId } }

    override suspend fun upsert(stats: ReadingStatsEntity) {
        rows[stats.bookId] = stats
        state.value++
    }

    override fun observeTotalMinutesReadForUser(userId: String): Flow<Long?> = state.map { rows.values.filter { it.userId == userId }.sumOf { it.totalMinutesRead } }

    override suspend fun deleteForBook(
        bookId: String,
        userId: String,
    ) {
        if (rows[bookId]?.userId == userId) rows.remove(bookId)
        state.value++
    }
}

/** In-memory [ReadingSessionDao] scoped by `userId`. */
open class FakeReadingSessionDao : ReadingSessionDao {
    val rows = mutableMapOf<String, ReadingSessionEntity>()

    private val state = MutableStateFlow(0)

    override suspend fun insert(session: ReadingSessionEntity) {
        rows[session.id] = session
        state.value++
    }

    /** Alias of [insert] kept for tests written against the older name. */
    suspend fun upsert(session: ReadingSessionEntity) = insert(session)

    override fun getTotalMinutesForDateAndUser(
        date: Long,
        userId: String,
    ): Flow<Int> = state.map { rows.values.filter { it.userId == userId && it.date == date }.sumOf { it.durationMinutes } }

    override fun getTotalMinutes(userId: String): Flow<Int> = state.map { rows.values.filter { it.userId == userId }.sumOf { it.durationMinutes } }

    override fun getSessionCount(userId: String): Flow<Int> = state.map { rows.values.count { it.userId == userId } }

    override fun getSessionCountForDateAndUser(
        date: Long,
        userId: String,
    ): Flow<Int> = state.map { rows.values.count { it.userId == userId && it.date == date } }

    override fun observeSessionsForBook(
        bookId: String,
        userId: String,
    ): Flow<List<ReadingSessionEntity>> = state.map { rows.values.filter { it.userId == userId && it.bookId == bookId }.sortedByDescending { it.startTimeEpochMillis } }

    override suspend fun deleteSessionsForBook(
        bookId: String,
        userId: String,
    ) {
        rows.values.filter { it.userId == userId && it.bookId == bookId }.forEach { rows.remove(it.id) }
        state.value++
    }

    override suspend fun count(userId: String): Int = rows.values.count { it.userId == userId }

    override suspend fun getDailyMinutes(userId: String): List<DailyReadingMinutes> =
        rows.values
            .filter { it.userId == userId }
            .groupBy { it.date }
            .map { (date, list) -> DailyReadingMinutes(dateEpochMillis = date, totalMinutes = list.sumOf { it.durationMinutes }) }
            .sortedByDescending { it.dateEpochMillis }

    override suspend fun getDailyMinutesFromDate(
        startDate: Long,
        userId: String,
    ): List<DailyReadingMinutes> =
        rows.values
            .filter { it.userId == userId && it.date >= startDate }
            .groupBy { it.date }
            .map { (date, list) -> DailyReadingMinutes(dateEpochMillis = date, totalMinutes = list.sumOf { it.durationMinutes }) }
            .sortedBy { it.dateEpochMillis }

    override suspend fun getById(
        id: String,
        userId: String,
    ): ReadingSessionEntity? = rows[id]?.takeIf { it.userId == userId }

    override suspend fun getAll(userId: String): List<ReadingSessionEntity> = rows.values.filter { it.userId == userId }

    override suspend fun getDailyMinutesForUser(userId: String): List<DailyReadingMinutes> = getDailyMinutes(userId)
}

/** In-memory [HighlightDao] scoped by `userId`. */
open class FakeHighlightDao : HighlightDao {
    val rows = mutableMapOf<String, HighlightEntity>()

    private val state = MutableStateFlow(0)

    private fun live(userId: String) = rows.values.filter { it.userId == userId && it.deletedAtEpochMillis == null }

    override fun observeAllHighlights(userId: String): Flow<List<HighlightEntity>> = state.map { live(userId).sortedByDescending { it.updatedAtEpochMillis } }

    override fun observeAllHighlightsPaged(userId: String) = com.nexo.testutil.FakePagingSource(listOf<HighlightEntity>())

    override fun observeHighlightsForBook(
        bookId: String,
        userId: String,
    ): Flow<List<HighlightEntity>> = state.map { live(userId).filter { it.bookId == bookId } }

    override suspend fun getHighlightById(
        id: String,
        userId: String,
    ): HighlightEntity? = rows[id]?.takeIf { it.userId == userId }

    override suspend fun deleteById(
        id: String,
        userId: String,
    ) {
        if (rows[id]?.userId == userId) rows.remove(id)
        state.value++
    }

    override suspend fun getHighlightsForBook(
        bookId: String,
        userId: String,
    ): List<HighlightEntity> = rows.values.filter { it.userId == userId && it.bookId == bookId }

    override suspend fun upsert(highlight: HighlightEntity) {
        rows[highlight.id] = highlight
        state.value++
    }

    override suspend fun upsertAll(highlights: List<HighlightEntity>) {
        highlights.forEach { rows[it.id] = it }
        state.value++
    }

    override suspend fun count(userId: String): Int = rows.values.count { it.userId == userId }

    override fun observeAllTags(userId: String): Flow<List<String>> =
        state.map {
            live(userId)
                .mapNotNull { it.tag }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
}

/** In-memory [BookmarkDao] scoped by `userId`. */
open class FakeBookmarkDao : BookmarkDao {
    val rows = mutableMapOf<String, BookmarkEntity>()

    private val state = MutableStateFlow(0)

    private fun live(userId: String) = rows.values.filter { it.userId == userId && it.deletedAtEpochMillis == null }

    override fun observeAllBookmarks(userId: String): Flow<List<BookmarkEntity>> = state.map { live(userId).sortedByDescending { it.updatedAtEpochMillis } }

    override fun observeBookmarksForBook(
        bookId: String,
        userId: String,
    ): Flow<List<BookmarkEntity>> = state.map { live(userId).filter { it.bookId == bookId } }

    override suspend fun getBookmarkById(
        id: String,
        userId: String,
    ): BookmarkEntity? = rows[id]?.takeIf { it.userId == userId }

    override suspend fun deleteById(
        id: String,
        userId: String,
    ) {
        if (rows[id]?.userId == userId) rows.remove(id)
        state.value++
    }

    override suspend fun getBookmarksForBook(
        bookId: String,
        userId: String,
    ): List<BookmarkEntity> = rows.values.filter { it.userId == userId && it.bookId == bookId }

    override suspend fun upsert(bookmark: BookmarkEntity) {
        rows[bookmark.id] = bookmark
        state.value++
    }

    override suspend fun upsertAll(bookmarks: List<BookmarkEntity>) {
        bookmarks.forEach { rows[it.id] = it }
        state.value++
    }

    override suspend fun count(userId: String): Int = rows.values.count { it.userId == userId }
}

/** In-memory [DictionaryWordDao] scoped by `userId`. */
open class FakeDictionaryWordDao : DictionaryWordDao {
    val rows = mutableMapOf<String, DictionaryWordEntity>()

    private val state = MutableStateFlow(0)

    override suspend fun insertRaw(word: DictionaryWordEntity) {
        rows[word.id] = word
        state.value++
    }

    override fun observeAll(userId: String): Flow<List<DictionaryWordEntity>> = state.map { rows.values.filter { it.userId == userId }.sortedByDescending { it.addedAtEpochMillis } }

    override fun search(
        normalizedQuery: String,
        userId: String,
    ): Flow<List<DictionaryWordEntity>> =
        state.map {
            rows.values
                .filter { it.userId == userId && it.wordNormalized.startsWith(normalizedQuery) }
                .sortedByDescending { it.addedAtEpochMillis }
        }

    override suspend fun delete(
        wordId: String,
        userId: String,
    ) {
        if (rows[wordId]?.userId == userId) rows.remove(wordId)
        state.value++
    }

    override suspend fun existsNormalized(
        normalizedWord: String,
        userId: String,
    ): Boolean = rows.values.any { it.userId == userId && it.wordNormalized == normalizedWord }

    override suspend fun findById(
        wordId: String,
        userId: String,
    ): DictionaryWordEntity? = rows[wordId]?.takeIf { it.userId == userId }

    override suspend fun getAll(userId: String): List<DictionaryWordEntity> = rows.values.filter { it.userId == userId }

    override suspend fun updateUserFields(
        wordId: String,
        userId: String,
        definition: String?,
        partOfSpeech: String?,
        phonetic: String?,
        example: String?,
        updatedAtEpochMillis: Long,
    ) {
        rows[wordId]?.takeIf { it.userId == userId }?.let {
            rows[wordId] =
                it.copy(
                    definition = definition,
                    partOfSpeech = partOfSpeech,
                    phonetic = phonetic,
                    example = example,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                )
        }
        state.value++
    }

    override suspend fun searchFtsRaw(query: androidx.sqlite.db.SupportSQLiteQuery): List<DictionaryWordEntity> = emptyList()

    override suspend fun searchFts(
        query: String,
        userId: String,
    ): List<DictionaryWordEntity> = emptyList()
}

/** In-memory [SyncOutboxDao] scoped by `userId`. */
open class FakeSyncOutboxDao : SyncOutboxDao {
    val items = mutableListOf<SyncOutboxEntity>()

    /** How many times [incrementRetryCount] ran, for retry-path assertions. */
    var incrementCalls = 0
        private set

    /** How many times [pruneFailedItems] ran, for retry-path assertions. */
    var pruneCalls = 0
        private set

    private val state = MutableStateFlow(0)

    override suspend fun getPendingItems(userId: String): List<SyncOutboxEntity> = items.filter { it.userId == userId }.sortedBy { it.createdAtEpochMillis }

    override suspend fun insert(item: SyncOutboxEntity) {
        items.add(item)
        state.value++
    }

    override suspend fun deleteById(
        id: String,
        userId: String,
    ) {
        items.removeAll { it.id == id && it.userId == userId }
        state.value++
    }

    override suspend fun incrementRetryCount(
        id: String,
        userId: String,
        error: String,
    ) {
        incrementCalls++
        val idx = items.indexOfFirst { it.id == id && it.userId == userId }
        if (idx >= 0) items[idx] = items[idx].copy(retryCount = items[idx].retryCount + 1, lastError = error)
        state.value++
    }

    override suspend fun pruneFailedItems(
        userId: String,
        maxRetries: Int,
    ) {
        pruneCalls++
        items.removeAll { it.userId == userId && it.retryCount >= maxRetries }
        state.value++
    }

    override fun observePendingCount(userId: String): Flow<Int> = state.map { items.count { it.userId == userId } }

    override suspend fun getByTypeAndEntityId(
        type: String,
        entityId: String,
        userId: String,
    ): SyncOutboxEntity? = items.firstOrNull { it.userId == userId && it.entityType == type && it.entityId == entityId }

    override suspend fun updatePayload(
        id: String,
        userId: String,
        payloadJson: String,
    ) {
        val idx = items.indexOfFirst { it.id == id && it.userId == userId }
        if (idx >= 0) items[idx] = items[idx].copy(payloadJson = payloadJson, retryCount = 0, lastError = null)
        state.value++
    }
}
