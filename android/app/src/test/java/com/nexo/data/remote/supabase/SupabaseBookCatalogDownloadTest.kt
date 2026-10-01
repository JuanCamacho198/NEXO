package com.nexo.data.remote.supabase

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.remote.sync.StorageSyncRemoteDataSource
import com.nexo.data.session.SessionManager
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import com.nexo.domain.model.AuthSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Unit tests for [SupabaseBookCatalogSync.downloadRemoteBook] — the Drive
 * download path introduced for cross-device library sync.
 *
 * Covers:
 *  - D4: 401/403 refresh-then-retry-once in downloadWithRetry.
 *  - D5: local cover is sourced from the catalog `coverUrl`.
 *  - D6: a locally DELETE-marked book is never resurrected by a download.
 */
class SupabaseBookCatalogDownloadTest {
    private lateinit var fakeBookDao: FakeBookDao
    private lateinit var mockSession: SessionManager
    private lateinit var mockCatalog: SupabaseBookCatalogDataSource
    private lateinit var mockRemote: StorageSyncRemoteDataSource
    private lateinit var mockProgress: SupabaseProgressDataSource
    private lateinit var localDir: java.io.File
    private var refreshCalls = 0

    private val session = AuthSession(userId = "test-user", email = "test@example.com")

    @Before
    fun setUp() {
        fakeBookDao = FakeBookDao()
        mockSession = mockk(relaxed = true)
        mockCatalog = mockk(relaxed = true)
        mockRemote = mockk(relaxed = true)
        mockProgress = mockk(relaxed = true)
        localDir = createTempDir()
        refreshCalls = 0

        coEvery { mockSession.getCurrentSession() } returns Result.success(session)
    }

    @After
    fun tearDown() {
        localDir.deleteRecursively()
        io.mockk.unmockkAll()
    }

    private fun newSync(): SupabaseBookCatalogSync =
        SupabaseBookCatalogSync(
            outboxDao = FakeSyncOutboxDao(),
            bookDao = fakeBookDao,
            sessionManager = mockSession,
            dataSource = mockCatalog,
            remoteDataSource = mockRemote,
            localBooksDir = localDir,
            driveTokenRefresher = {
                refreshCalls++
                Result.success("refreshed-token")
            },
        )

    // ─── D6: tombstone guard ───────────────────────────────────────

    @Test
    fun download_tombstonedBookRejectedWithoutCallingRemote() =
        runBlocking {
            fakeBookDao.upsert(sampleBook("dead-1").copy(deletedAtEpochMillis = 1234L))

            val result = newSync().downloadRemoteBook("dead-1")

            assertTrue(result.isFailure)
            assertEquals("BOOK_TOMBSTONED", (result.exceptionOrNull() as? AppError)?.code)
            coVerify(inverse = true) { mockRemote.download(any()) }
        }

    @Test
    fun download_rejectsDeletedRowWithoutCallingRemote() =
        runBlocking {
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow(id = "deleted-1", filePath = null).copy(lifecycle = "deleted"),
                )

            val result = newSync().downloadRemoteBook("deleted-1")

            assertTrue(result.isFailure)
            assertEquals("BOOK_NOT_IN_CATALOG", (result.exceptionOrNull() as? AppError)?.code)
            coVerify(inverse = true) { mockRemote.download(any()) }
        }

    @Test
    fun download_rejectsUnavailableRowWithoutCallingRemote() =
        runBlocking {
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow(id = "unavailable-1", filePath = null).copy(lifecycle = "unavailable"),
                )

            val result = newSync().downloadRemoteBook("unavailable-1")

            assertTrue(result.isFailure)
            assertEquals("UNAVAILABLE", (result.exceptionOrNull() as? AppError)?.code)
            coVerify(inverse = true) { mockRemote.download(any()) }
        }

    @Test
    fun download_importedRowWithNullFilePathSucceedsAndPersistsRealTitle() =
        runBlocking {
            val bytes = "imported-bytes".toByteArray()
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow(id = "imported-1", filePath = null).copy(
                        lifecycle = "imported",
                        title = "Pensar rápido, pensar despacio",
                        contentHash = sha256(bytes),
                    ),
                )
            coEvery { mockRemote.download(any()) } returns bytes

            val result = newSync().downloadRemoteBook("imported-1")

            assertTrue(result.isSuccess)
            val book = fakeBookDao.getBookById("imported-1")
            assertEquals("Pensar rápido, pensar despacio", book?.title)
            coVerify(exactly = 1) { mockRemote.download("books/test-user/imported-1.epub") }
        }

    @Test
    fun download_preservesPermissionDenied_distinctFromUnavailable() =
        runBlocking {
            val bytes = "bytes".toByteArray()
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow("permission", "/permission.epub").copy(contentHash = sha256(bytes)),
                )
            coEvery { mockRemote.download(any()) } throws
                AppError(
                    ErrorCategory.STORAGE,
                    "PERMISSION_DENIED",
                    "Drive access denied",
                    "test",
                )
            val result = newSync().downloadRemoteBook("permission")
            assertEquals("PERMISSION_DENIED", (result.exceptionOrNull() as? AppError)?.code)
            assertEquals(ErrorCategory.STORAGE, (result.exceptionOrNull() as? AppError)?.category)
            assertTrue(fakeBookDao.getBookById("permission") == null)
        }

    // ─── D4: refresh-then-retry on 401/403 ──────────────────────────────────

    @Test
    fun download_refreshesOnceThenRetriesAndSucceeds() =
        runBlocking {
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow(id = "auth-1", filePath = "/auth-1.epub").copy(contentHash = sha256("file-bytes".toByteArray())),
                )
            var calls = 0
            coEvery { mockRemote.download("books/test-user/auth-1.epub") } answers {
                calls++
                if (calls == 1) {
                    throw AppError(
                        category = ErrorCategory.AUTH,
                        code = "GOOGLE_DRIVE_UNAUTHORIZED",
                        message = "401",
                        component = "test",
                    )
                }
                "file-bytes".toByteArray()
            }

            val result = newSync().downloadRemoteBook("auth-1")

            assertTrue(result.isSuccess)
            assertEquals(1, refreshCalls)
            coVerify(exactly = 2) { mockRemote.download("books/test-user/auth-1.epub") }
        }

    @Test
    fun download_surfacesFailureWhenRefreshFails() =
        runBlocking {
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow(id = "auth-2", filePath = "/auth-2.epub"),
                )
            coEvery { mockRemote.download(any()) } throws
                AppError(
                    category = ErrorCategory.AUTH,
                    code = "GOOGLE_DRIVE_UNAUTHORIZED",
                    message = "401",
                    component = "test",
                )
            val sync =
                SupabaseBookCatalogSync(
                    outboxDao = FakeSyncOutboxDao(),
                    bookDao = fakeBookDao,
                    sessionManager = mockSession,
                    dataSource = mockCatalog,
                    remoteDataSource = mockRemote,
                    localBooksDir = localDir,
                    driveTokenRefresher = {
                        refreshCalls++
                        Result.failure(AppError(ErrorCategory.AUTH, "REFRESH_FAILED", "no refresh token", "test"))
                    },
                )

            val result = sync.downloadRemoteBook("auth-2")

            assertTrue(result.isFailure)
            assertEquals("DOWNLOAD_ERROR", (result.exceptionOrNull() as? AppError)?.code)
            assertEquals(1, refreshCalls)
        }

    // ─── D5: cover sourced from catalog coverUrl ──────────────────────────

    @Test
    fun download_persistsCoverFromCatalog() =
        runBlocking {
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow(id = "cover-1", filePath = "/cover-1.epub", coverUrl = "https://cdn.example/cover-1.jpg")
                        .copy(contentHash = sha256("bytes".toByteArray())),
                )
            coEvery { mockRemote.download(any()) } returns "bytes".toByteArray()

            val result = newSync().downloadRemoteBook("cover-1")

            assertTrue(result.isSuccess)
            assertEquals("https://cdn.example/cover-1.jpg", fakeBookDao.getBookById("cover-1")?.coverPath)
        }

    @Test
    fun download_hashMismatch_removesTempAndDoesNotPersist() =
        runBlocking {
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow("bad-hash", "/bad-hash.epub").copy(contentHash = "00"),
                )
            coEvery { mockRemote.download(any()) } returns "bytes".toByteArray()

            val result = newSync().downloadRemoteBook("bad-hash")

            assertEquals("HASH_MISMATCH", (result.exceptionOrNull() as? AppError)?.code)
            assertTrue(!File(localDir, "bad-hash.epub").exists())
            assertTrue(!File(localDir, ".bad-hash.epub.part").exists())
            assertTrue(fakeBookDao.getBookById("bad-hash") == null)
        }

    @Test
    fun download_requiresNonEmptySha256_beforePersistence() =
        runBlocking {
            coEvery { mockCatalog.listUserBooks("test-user") } returns listOf(catalogRow("missing-hash", "/missing.epub"))
            coEvery { mockRemote.download(any()) } returns "bytes".toByteArray()
            val result = newSync().downloadRemoteBook("missing-hash")
            assertEquals("HASH_REQUIRED", (result.exceptionOrNull() as? AppError)?.code)
            assertTrue(fakeBookDao.getBookById("missing-hash") == null)
            assertTrue(localDir.listFiles()?.none { it.name.contains("missing-hash") } != false)
        }

    @Test
    fun download_dbFailure_rollsBackFileAndRetryArtifacts() =
        runBlocking {
            val bytes = "bytes".toByteArray()
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow("db-failure", "/db-failure.epub").copy(contentHash = sha256(bytes)),
                )
            coEvery { mockRemote.download(any()) } returns bytes
            fakeBookDao.failUpsert = true
            val result = newSync().downloadRemoteBook("db-failure")
            assertTrue(result.isFailure)
            assertTrue(localDir.listFiles()?.none { it.name.contains("db-failure") } != false)
        }

    @Test
    fun download_persistsCompleteRemoteMapping() =
        runBlocking {
            val bytes = "bytes".toByteArray()
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow("mapping", "/mapping.epub").copy(
                        contentHash = sha256(bytes),
                        remoteProvider = "google_drive",
                        remoteFileId = "drive-1",
                        remotePath = "Nexo/Books/mapping.epub",
                        protocolVersion = "7",
                    ),
                )
            coEvery { mockRemote.download(any()) } returns bytes
            assertTrue(newSync().downloadRemoteBook("mapping").isSuccess)
            val book = fakeBookDao.getBookById("mapping")!!
            assertEquals("google_drive", book.remoteProvider)
            assertEquals(7, book.remoteProtocolVersion)
            assertEquals("drive-1", book.remoteFileId)
        }

    @Test
    fun download_seedsContinueReadingFromRemoteProgress() =
        runBlocking {
            val bytes = "continue".toByteArray()
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow("seed-1", "/seed-1.epub").copy(contentHash = sha256(bytes)),
                )
            coEvery { mockRemote.download(any()) } returns bytes
            coEvery { mockProgress.getProgress("test-user", "seed-1") } returns
                ReadingProgressRow(
                    userId = "test-user",
                    bookId = "seed-1",
                    cfiLocation = "epubcfi(/6/5!/4/2)",
                    percentage = 42.0,
                    updatedAt = "2026-08-18T12:00:00.000Z",
                    locatorJson = null,
                )

            val sync =
                SupabaseBookCatalogSync(
                    outboxDao = FakeSyncOutboxDao(),
                    bookDao = fakeBookDao,
                    sessionManager = mockSession,
                    dataSource = mockCatalog,
                    remoteDataSource = mockRemote,
                    localBooksDir = localDir,
                    progressDataSource = mockProgress,
                )

            assertTrue(sync.downloadRemoteBook("seed-1").isSuccess)
            val book = fakeBookDao.getBookById("seed-1")!!
            assertEquals("reading", book.readingState)
            assertEquals(42f, book.progressPercentage)
        }

    @Test
    fun download_doesNotSeedContinue_whenNoRemoteProgress() =
        runBlocking {
            val bytes = "nostart".toByteArray()
            coEvery { mockCatalog.listUserBooks("test-user") } returns
                listOf(
                    catalogRow("noseed-1", "/noseed-1.epub").copy(contentHash = sha256(bytes)),
                )
            coEvery { mockRemote.download(any()) } returns bytes
            coEvery { mockProgress.getProgress("test-user", "noseed-1") } returns null

            val sync =
                SupabaseBookCatalogSync(
                    outboxDao = FakeSyncOutboxDao(),
                    bookDao = fakeBookDao,
                    sessionManager = mockSession,
                    dataSource = mockCatalog,
                    remoteDataSource = mockRemote,
                    localBooksDir = localDir,
                    progressDataSource = mockProgress,
                )

            assertTrue(sync.downloadRemoteBook("noseed-1").isSuccess)
            val book = fakeBookDao.getBookById("noseed-1")!!
            assertEquals("to_read", book.readingState)
            assertEquals(0f, book.progressPercentage)
        }

    private fun sha256(bytes: ByteArray) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ─── Factory helpers ───────────────────────────────────────────────────

    private fun sampleBook(id: String): BookEntity =
        BookEntity(
            id = id,
            title = "Book $id",
            author = "Author",
            coverPath = null,
            filePath = "/$id.epub",
            format = "epub",
            totalPages = 100,
            description = "Description",
            updatedAtEpochMillis = 1000L,
            deletedAtEpochMillis = null,
        )

    private fun catalogRow(
        id: String,
        filePath: String?,
        coverUrl: String? = null,
    ): UserBookRow =
        UserBookRow(
            id = id,
            userId = "test-user",
            title = "Book $id",
            author = "Author",
            format = "epub",
            contentHash = null,
            filePath = filePath,
            coverUrl = coverUrl,
            description = null,
            totalPages = 100,
            sourceDevice = "desktop",
            importedAt = "2026-07-12T12:00:00.000Z",
            updatedAt = "2026-07-12T12:00:00.000Z",
        )

    // ── Fake BookDao ───────────────────────────────────────────────────────
    private class FakeBookDao : BookDao {
        private val byId = mutableMapOf<String, BookEntity>()
        var failUpsert = false

        override suspend fun getBookById(bookId: String): BookEntity? = byId[bookId]

        override suspend fun upsert(book: BookEntity) {
            if (failUpsert) throw IllegalStateException("db down")
            byId[book.id] = book
        }

        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { upsert(it) }
        }

        override fun observeAllBooks(): Flow<List<BookEntity>> = MutableStateFlow(byId.values.toList())

        override fun observeReadingBooks(): Flow<List<BookEntity>> =
            MutableStateFlow(
                byId.values.filter { it.deletedAtEpochMillis == null && it.readingState == "reading" && it.progressPercentage < 100f },
            )

        override fun observeBookById(bookId: String): Flow<BookEntity?> = MutableStateFlow(byId[bookId])

        override fun observeAllBooksPaged(): androidx.paging.PagingSource<Int, BookEntity> = com.nexo.testutil.FakePagingSource(emptyList())

        override suspend fun deleteBook(
            bookId: String,
            deletedAt: Long,
        ) {
            val existing = byId[bookId] ?: return
            byId[bookId] = existing.copy(deletedAtEpochMillis = deletedAt)
        }

        override suspend fun deleteById(bookId: String) {
            byId.remove(bookId)
        }

        override suspend fun updateRating(
            bookId: String,
            rating: Int?,
        ) {
            val existing = byId[bookId] ?: return
            byId[bookId] = existing.copy(userRating = rating)
        }

        override suspend fun updateStatus(
            bookId: String,
            status: String?,
            updatedAt: Long,
        ) {
            val existing = byId[bookId] ?: return
            byId[bookId] = existing.copy(status = status, updatedAtEpochMillis = updatedAt)
        }

        override suspend fun startReading(
            bookId: String,
            updatedAt: Long,
        ) {
            val existing = byId[bookId] ?: return
            byId[bookId] = existing.copy(readingState = "reading", startedAtEpochMillis = updatedAt, updatedAtEpochMillis = updatedAt)
        }

        override suspend fun updateReadingProgress(
            bookId: String,
            progress: Float,
            updatedAt: Long,
        ) {
            val existing = byId[bookId] ?: return
            val readingState = if (progress >= 100f) "completed" else "reading"
            byId[bookId] =
                existing.copy(
                    readingState = readingState,
                    progressPercentage = progress.coerceIn(0f, 100f),
                    progressUpdatedAtEpochMillis = updatedAt,
                    completedAtEpochMillis = if (progress >= 100f) updatedAt else existing.completedAtEpochMillis,
                    updatedAtEpochMillis = updatedAt,
                )
        }

        override suspend fun completeReading(
            bookId: String,
            updatedAt: Long,
        ) {
            val existing = byId[bookId] ?: return
            byId[bookId] =
                existing.copy(
                    readingState = "completed",
                    progressPercentage = 100f,
                    completedAtEpochMillis = updatedAt,
                    updatedAtEpochMillis = updatedAt,
                )
        }

        override suspend fun updateMetadata(
            bookId: String,
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
            val existing = byId[bookId] ?: return
            byId[bookId] =
                existing.copy(
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

        override suspend fun count(): Int = byId.size
    }

    private class FakeSyncOutboxDao : com.nexo.data.local.dao.SyncOutboxDao {
        override suspend fun getPendingItems(): List<com.nexo.data.local.entity.SyncOutboxEntity> = emptyList()

        override suspend fun insert(item: com.nexo.data.local.entity.SyncOutboxEntity) {}

        override suspend fun deleteById(id: String) {}

        override suspend fun incrementRetryCount(
            id: String,
            error: String,
        ) {}

        override suspend fun pruneFailedItems(maxRetries: Int) {}

        override fun observePendingCount(): Flow<Int> = MutableStateFlow(0)

        override suspend fun getByTypeAndEntityId(
            type: String,
            entityId: String,
        ): com.nexo.data.local.entity.SyncOutboxEntity? = null

        override suspend fun updatePayload(
            id: String,
            payloadJson: String,
        ) {}

        override suspend fun upsertCoalesced(item: com.nexo.data.local.entity.SyncOutboxEntity) {}
    }
}
