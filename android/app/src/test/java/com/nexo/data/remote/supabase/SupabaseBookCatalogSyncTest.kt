package com.nexo.data.remote.supabase

import com.nexo.data.local.dao.BookDao
import com.nexo.data.local.dao.SyncOutboxDao
import com.nexo.data.local.entity.BookEntity
import com.nexo.data.local.entity.SyncEntityType
import com.nexo.data.local.entity.SyncOperation
import com.nexo.data.local.entity.SyncOutboxEntity
import com.nexo.data.session.SessionManager
import com.nexo.domain.model.AuthSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SupabaseBookCatalogSync].
 *
 * Uses Fake DAOs (following existing project test patterns) and
 * mocks the data source to verify outbox processing and reconciliation.
 */
class SupabaseBookCatalogSyncTest {
    private lateinit var fakeBookDao: FakeBookDao
    private lateinit var fakeOutboxDao: FakeSyncOutboxDao
    private lateinit var mockSessionManager: SessionManager
    private lateinit var mockDataSource: SupabaseBookCatalogDataSource
    private lateinit var sync: SupabaseBookCatalogSync

    @Before
    fun setUp() {
        fakeBookDao = FakeBookDao()
        fakeOutboxDao = FakeSyncOutboxDao()
        mockSessionManager = mockk(relaxed = true)
        mockDataSource = mockk(relaxed = true)

        coEvery { mockSessionManager.ensureFreshSession() } returns
            Result.success(
                AuthSession(userId = "test-user", email = "test@example.com"),
            )

        sync =
            SupabaseBookCatalogSync(
                outboxDao = fakeOutboxDao,
                bookDao = fakeBookDao,
                sessionManager = mockSessionManager,
                dataSource = mockDataSource,
            )
    }

    @After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    // ─── Outbox Processing ───────────────────────────────────────

    @Test
    fun processOutbox_upsertsBookAndDeletesOutboxEntry() =
        runBlocking {
            val book = createSampleBook("book-1")
            fakeBookDao.upsert(book)

            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-1",
                    entityType = SyncEntityType.BOOK.name,
                    entityId = "book-1",
                    operation = SyncOperation.CREATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 100L,
                ),
            )

            coEvery { mockDataSource.upsertBook(any()) } returns mockk()

            sync.startProcessing()
            // Wait for processing to complete (runs on IO dispatcher)
            Thread.sleep(500)

            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(0, pendingItems.size)
            coVerify { mockDataSource.upsertBook(any()) }
        }

    @Test
    fun processOutbox_skipsNonBookEntityTypes() =
        runBlocking {
            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-1",
                    entityType = "READING_PROGRESS",
                    entityId = "book-1",
                    operation = SyncOperation.UPDATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 100L,
                ),
            )

            sync.startProcessing()
            Thread.sleep(500)

            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(1, pendingItems.size)
            coVerify(inverse = true) { mockDataSource.upsertBook(any()) }
        }

    @Test
    fun processOutbox_handlesDeleteOperationAsTombstone() =
        runBlocking {
            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-2",
                    entityType = SyncEntityType.BOOK.name,
                    entityId = "book-1",
                    operation = SyncOperation.DELETE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 200L,
                ),
            )

            coEvery { mockDataSource.getUserBook("test-user", "book-1") } returns null
            coEvery { mockDataSource.upsertBook(any()) } returns mockk()

            sync.startProcessing()
            Thread.sleep(500)

            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(0, pendingItems.size)
            coVerify { mockDataSource.upsertBook(match { it.id == "book-1" && it.lifecycle == "deleted" }) }
            coVerify(inverse = true) { mockDataSource.deleteUserBook(any(), any()) }
        }

    @Test
    fun processOutbox_skipsWhenBookNotFoundLocally() =
        runBlocking {
            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-3",
                    entityType = SyncEntityType.BOOK.name,
                    entityId = "nonexistent-book",
                    operation = SyncOperation.CREATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 300L,
                ),
            )

            sync.startProcessing()
            Thread.sleep(500)

            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(0, pendingItems.size)
            coVerify(inverse = true) { mockDataSource.upsertBook(any()) }
        }

    @Test
    fun processOutbox_incrementsRetryOnFailure() =
        runBlocking {
            val book = createSampleBook("book-2")
            fakeBookDao.upsert(book)

            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-4",
                    entityType = SyncEntityType.BOOK.name,
                    entityId = "book-2",
                    operation = SyncOperation.CREATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 400L,
                ),
            )

            coEvery { mockDataSource.upsertBook(any()) } throws RuntimeException("Network error")

            sync.startProcessing()
            Thread.sleep(500)

            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(1, pendingItems.size)
            assertEquals(1, pendingItems.first().retryCount)
        }

    // ─── Content-Hash Dedup (PR 5) ────────────────────────────────

    @Test
    fun processOutbox_skipsUpsertWhenContentHashAlreadyExists() =
        runBlocking {
            val book = createSampleBook("book-hash-dup")
            fakeBookDao.upsert(book.copy(contentHash = "sha256:abc123"))

            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-dup",
                    entityType = SyncEntityType.BOOK.name,
                    entityId = "book-hash-dup",
                    operation = SyncOperation.CREATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 500L,
                ),
            )

            // Hash already exists in Supabase
            coEvery { mockDataSource.getUserBookByHash("test-user", "sha256:abc123") } returns
                UserBookRow(
                    id = "existing-book",
                    userId = "test-user",
                    title = "Existing Book",
                    author = "Author",
                    format = "epub",
                    contentHash = "sha256:abc123",
                    filePath = null,
                    coverUrl = null,
                    description = null,
                    totalPages = null,
                    sourceDevice = "android",
                    importedAt = "2026-07-12T12:00:00.000Z",
                    updatedAt = "2026-07-12T12:00:00.000Z",
                )

            sync.startProcessing()
            Thread.sleep(500)

            // Outbox entry should be deleted without calling upsert
            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(0, pendingItems.size)
            coVerify(inverse = true) { mockDataSource.upsertBook(any()) }
            coVerify { mockDataSource.getUserBookByHash("test-user", "sha256:abc123") }
        }

    @Test
    fun processOutbox_proceedsWithUpsertWhenContentHashNotInSupabase() =
        runBlocking {
            val book = createSampleBook("book-hash-new")
            fakeBookDao.upsert(book.copy(contentHash = "sha256:def456"))

            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-new",
                    entityType = SyncEntityType.BOOK.name,
                    entityId = "book-hash-new",
                    operation = SyncOperation.CREATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 600L,
                ),
            )

            // Hash not found in Supabase
            coEvery { mockDataSource.getUserBookByHash("test-user", "sha256:def456") } returns null
            coEvery { mockDataSource.upsertBook(any()) } returns mockk()

            sync.startProcessing()
            Thread.sleep(500)

            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(0, pendingItems.size)
            coVerify { mockDataSource.upsertBook(any()) }
            coVerify { mockDataSource.getUserBookByHash("test-user", "sha256:def456") }
        }

    @Test
    fun processOutbox_proceedsWithUpsertWhenContentHashIsNull() =
        runBlocking {
            // Backward compatible: books without content_hash still upsert normally
            val book = createSampleBook("book-no-hash")
            book.contentHash // null
            fakeBookDao.upsert(book)

            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-null-hash",
                    entityType = SyncEntityType.BOOK.name,
                    entityId = "book-no-hash",
                    operation = SyncOperation.CREATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 700L,
                ),
            )

            coEvery { mockDataSource.upsertBook(any()) } returns mockk()

            sync.startProcessing()
            Thread.sleep(500)

            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(0, pendingItems.size)
            coVerify { mockDataSource.upsertBook(any()) }
            // Should NOT call getUserBookByHash when contentHash is null
            coVerify(inverse = true) { mockDataSource.getUserBookByHash(any(), any()) }
        }

    @Test
    fun processOutbox_proceedsWithUpsertWhenHashMatchesOwnRow() =
        runBlocking {
            // Bug 2 fix: an UPDATE of the book's own row must proceed even when the
            // content hash is unchanged — dedup only skips OTHER rows, so the
            // cross-device CREATE dedup stays intact.
            val book = createSampleBook("book-own-update").copy(contentHash = "sha256:own123")
            fakeBookDao.upsert(book)

            fakeOutboxDao.insert(
                SyncOutboxEntity(
                    id = "outbox-own-update",
                    entityType = SyncEntityType.BOOK.name,
                    entityId = "book-own-update",
                    operation = SyncOperation.UPDATE.name,
                    payloadJson = "{}",
                    createdAtEpochMillis = 800L,
                ),
            )

            // Remote row for this hash IS the book's own row (same id).
            coEvery { mockDataSource.getUserBookByHash("test-user", "sha256:own123") } returns
                UserBookRow(
                    id = "book-own-update",
                    userId = "test-user",
                    title = "Book book-own-update",
                    author = "Author book-own-update",
                    format = "epub",
                    contentHash = "sha256:own123",
                    filePath = "books/user-1/book-own-update.epub",
                    coverUrl = null,
                    description = null,
                    totalPages = null,
                    sourceDevice = "android",
                    importedAt = "2026-07-12T12:00:00.000Z",
                    updatedAt = "2026-07-12T12:00:00.000Z",
                )
            coEvery { mockDataSource.upsertBook(any()) } returns mockk()

            sync.startProcessing()
            Thread.sleep(500)

            val pendingItems = fakeOutboxDao.getPendingItems()
            assertEquals(0, pendingItems.size)
            coVerify { mockDataSource.upsertBook(match { it.id == "book-own-update" }) }
            coVerify { mockDataSource.getUserBookByHash("test-user", "sha256:own123") }
        }

    // ─── Reconciliation ──────────────────────────────────────────

    @Test
    fun reconcile_pushesLocalBooksMissingFromRemote() =
        runBlocking {
            val localBook1 = createSampleBook("local-1")
            val localBook2 = createSampleBook("local-2")
            fakeBookDao.upsert(localBook1)
            fakeBookDao.upsert(localBook2)

            // Remote has only local-1
            coEvery { mockDataSource.listUserBooks("test-user") } returns
                listOf(
                    UserBookRow(
                        id = "local-1",
                        userId = "test-user",
                        title = "Book 1",
                        author = "Author 1",
                        format = "epub",
                        contentHash = null,
                        filePath = "/local-1.epub",
                        coverUrl = null,
                        description = null,
                        totalPages = null,
                        sourceDevice = "android",
                        importedAt = "2026-07-12T12:00:00.000Z",
                        updatedAt = "2026-07-12T12:00:00.000Z",
                    ),
                )
            coEvery { mockDataSource.upsertBook(any()) } returns mockk()

            sync.reconcileLocalBooks()

            // local-2 should be upserted (missing from remote)
            coVerify { mockDataSource.upsertBook(match { it.id == "local-2" }) }
        }

    @Test
    fun reconcile_doesNotPushAlreadySyncedBooks() =
        runBlocking {
            val localBook = createSampleBook("synced-1")
            fakeBookDao.upsert(localBook)

            // Remote has synced-1 already
            coEvery { mockDataSource.listUserBooks("test-user") } returns
                listOf(
                    UserBookRow(
                        id = "synced-1",
                        userId = "test-user",
                        title = "Synced Book",
                        author = "Author",
                        format = "epub",
                        contentHash = null,
                        filePath = "/synced-1.epub",
                        coverUrl = null,
                        description = null,
                        totalPages = null,
                        sourceDevice = "android",
                        importedAt = "2026-07-12T12:00:00.000Z",
                        updatedAt = "2026-07-12T12:00:00.000Z",
                    ),
                )

            sync.reconcileLocalBooks()

            coVerify(inverse = true) { mockDataSource.upsertBook(match { it.id == "synced-1" }) }
        }

    @Test
    fun reconcile_doesNothingWhenAllBooksMatch() =
        runBlocking {
            val localBook = createSampleBook("match-1")
            fakeBookDao.upsert(localBook)

            coEvery { mockDataSource.listUserBooks("test-user") } returns
                listOf(
                    UserBookRow(
                        id = "match-1",
                        userId = "test-user",
                        title = "Match",
                        author = "Author",
                        format = "epub",
                        contentHash = null,
                        filePath = "/match-1.epub",
                        coverUrl = null,
                        description = null,
                        totalPages = null,
                        sourceDevice = "android",
                        importedAt = "2026-07-12T12:00:00.000Z",
                        updatedAt = "2026-07-12T12:00:00.000Z",
                    ),
                )

            sync.reconcileLocalBooks()

            coVerify(inverse = true) { mockDataSource.upsertBook(match { it.id == "match-1" }) }
        }

    @Test
    fun reconcile_reinstallWithNoLocalBooksNeverDeletesRemoteRows() =
        runBlocking {
            coEvery { mockDataSource.listUserBooks("test-user") } returns
                listOf(
                    UserBookRow(
                        id = "remote-only",
                        userId = "test-user",
                        title = "Remote",
                        format = "epub",
                        importedAt = "2026-07-12T12:00:00.000Z",
                        updatedAt = "2026-07-12T12:00:00.000Z",
                    ),
                )

            sync.reconcileLocalBooks()

            coVerify(inverse = true) { mockDataSource.deleteUserBook(any(), any()) }
        }

    @Test
    fun reconcile_skipsWhenNoSession() =
        runBlocking {
            coEvery { mockSessionManager.ensureFreshSession() } returns
                Result.failure(
                    Exception("Not signed in"),
                )

            sync.reconcileLocalBooks()

            coVerify(inverse = true) { mockDataSource.listUserBooks(any()) }
        }

    // ─── Bootstrap ───────────────────────────────────────────────

    @Test
    fun bootstrap_runsReconcileThenStartsProcessing() =
        runBlocking {
            val book = createSampleBook("boot-1")
            fakeBookDao.upsert(book)

            coEvery { mockDataSource.listUserBooks("test-user") } returns emptyList()
            coEvery { mockDataSource.upsertBook(any()) } returns mockk()

            sync.bootstrap()

            // Reconciliation should push boot-1 since remote is empty
            coVerify { mockDataSource.upsertBook(match { it.id == "boot-1" }) }
        }

    // ─── Fetch Catalog ───────────────────────────────────────────

    @Test
    fun fetchCatalog_returnsEmptyWhenNoSession() =
        runBlocking {
            coEvery { mockSessionManager.ensureFreshSession() } returns
                Result.failure(
                    Exception("No session"),
                )

            val result = sync.fetchCatalog()
            assertTrue(result.isEmpty())
        }

    @Test
    fun fetchCatalog_delegatesToDataSource() =
        runBlocking {
            coEvery { mockDataSource.listUserBooks("test-user") } returns
                listOf(
                    UserBookRow(
                        id = "remote-1",
                        userId = "test-user",
                        title = "Remote Book",
                        author = "Author",
                        format = "pdf",
                        contentHash = null,
                        filePath = "/remote-1.pdf",
                        coverUrl = null,
                        description = null,
                        totalPages = null,
                        sourceDevice = "desktop",
                        importedAt = "2026-07-12T12:00:00.000Z",
                        updatedAt = "2026-07-12T12:00:00.000Z",
                    ),
                )

            val result = sync.fetchCatalog()

            assertEquals(1, result.size)
            assertEquals("remote-1", result.first().id)
            coVerify { mockDataSource.listUserBooks("test-user") }
        }

    // ─── Realtime apply-if-newer (PR5) ──────────────────────────────

    @Test
    fun applyRemoteBook_missingLocalNeverBecomesDeletion() =
        runBlocking {
            val row =
                UserBookRow(
                    id = "ghost",
                    userId = "test-user",
                    title = "Ghost",
                    format = "epub",
                    lifecycle = "deleted",
                    catalogVersion = 9,
                    importedAt = "2026-07-12T12:00:00.000Z",
                    updatedAt = "2026-07-12T12:00:00.000Z",
                )
            sync.applyRemoteBook(row)

            assertEquals(0, fakeBookDao.count()) // no local row created, never a deletion
            coVerify(inverse = true) { mockDataSource.deleteUserBook(any(), any()) }
        }

    @Test
    fun applyRemoteBook_staleEventIsIgnored() =
        runBlocking {
            val existing = createSampleBook("book-v5").copy(remoteCatalogVersion = 5L)
            fakeBookDao.upsert(existing)

            sync.applyRemoteBook(
                UserBookRow(
                    id = "book-v5",
                    userId = "test-user",
                    title = "Stale",
                    format = "epub",
                    lifecycle = "available",
                    catalogVersion = 4,
                    coverUrl = "https://stale.example/c.jpg",
                    importedAt = "2026-07-12T12:00:00.000Z",
                    updatedAt = "2026-07-12T12:00:00.000Z",
                ),
            )

            val local = fakeBookDao.getBookById("book-v5")
            assertEquals(5L, local?.remoteCatalogVersion)
            assertEquals(null, local?.coverPath) // stale cover not applied
        }

    @Test
    fun applyRemoteBook_equalVersionIsIdempotent() =
        runBlocking {
            val existing = createSampleBook("book-v5").copy(remoteCatalogVersion = 5L)
            fakeBookDao.upsert(existing)

            sync.applyRemoteBook(
                UserBookRow(
                    id = "book-v5",
                    userId = "test-user",
                    title = "Equal",
                    format = "epub",
                    lifecycle = "available",
                    catalogVersion = 5,
                    coverUrl = "https://equal.example/c.jpg",
                    importedAt = "2026-07-12T12:00:00.000Z",
                    updatedAt = "2026-07-12T12:00:00.000Z",
                ),
            )

            val local = fakeBookDao.getBookById("book-v5")
            assertEquals(null, local?.coverPath) // no change on equal version
        }

    @Test
    fun applyRemoteBook_newerTombstoneDeletesLocalBook() =
        runBlocking {
            val existing = createSampleBook("book-del").copy(remoteCatalogVersion = 3L)
            fakeBookDao.upsert(existing)

            sync.applyRemoteBook(
                UserBookRow(
                    id = "book-del",
                    userId = "test-user",
                    title = "Gone",
                    format = "epub",
                    lifecycle = "deleted",
                    catalogVersion = 4,
                    importedAt = "2026-07-12T12:00:00.000Z",
                    updatedAt = "2026-07-12T12:00:00.000Z",
                ),
            )

            assertTrue(fakeBookDao.getBookById("book-del")?.deletedAtEpochMillis != null)
        }

    @Test
    fun applyRemoteBook_newerEventFillsMissingCoverAndAppliesVersion() =
        runBlocking {
            // Existing local cover is MISSING (null) → remote coverUrl fills it.
            val existing = createSampleBook("book-cov").copy(remoteCatalogVersion = 1L)
            fakeBookDao.upsert(existing)

            sync.applyRemoteBook(
                UserBookRow(
                    id = "book-cov",
                    userId = "test-user",
                    title = "Covered",
                    format = "epub",
                    lifecycle = "available",
                    catalogVersion = 2,
                    coverUrl = "https://cdn.example/c.jpg",
                    coverObjectPath = "user-1/book-cov/cover.jpg",
                    remoteFileId = "file-1",
                    importedAt = "2026-07-12T12:00:00.000Z",
                    updatedAt = "2026-07-12T12:00:00.000Z",
                ),
            )

            val local = fakeBookDao.getBookById("book-cov")
            assertEquals(2L, local?.remoteCatalogVersion)
            assertEquals("https://cdn.example/c.jpg", local?.coverPath)
            assertEquals("user-1/book-cov/cover.jpg", local?.remoteCoverRef)
            assertEquals("file-1", local?.remoteFileId)
        }

    @Test
    fun applyRemoteBook_newerEventPreservesExistingLocalCover() =
        runBlocking {
            // Existing local cover is PRESENT (working local file) → remote coverUrl
            // must NOT clobber it, even though the remote event is newer.
            val existing =
                createSampleBook("book-cov").copy(
                    remoteCatalogVersion = 1L,
                    coverPath = "/data/user/0/com.nexo/files/covers/book-cov.jpg",
                )
            fakeBookDao.upsert(existing)

            sync.applyRemoteBook(
                UserBookRow(
                    id = "book-cov",
                    userId = "test-user",
                    title = "Covered",
                    format = "epub",
                    lifecycle = "available",
                    catalogVersion = 2,
                    coverUrl = "https://cdn.example/c.jpg",
                    coverObjectPath = "user-1/book-cov/cover.jpg",
                    remoteFileId = "file-1",
                    importedAt = "2026-07-12T12:00:00.000Z",
                    updatedAt = "2026-07-12T12:00:00.000Z",
                ),
            )

            val local = fakeBookDao.getBookById("book-cov")
            assertEquals(2L, local?.remoteCatalogVersion)
            // Working local cover wins over the remote URL.
            assertEquals("/data/user/0/com.nexo/files/covers/book-cov.jpg", local?.coverPath)
            assertEquals("user-1/book-cov/cover.jpg", local?.remoteCoverRef)
            assertEquals("file-1", local?.remoteFileId)
        }

    // ─── Factory helpers ─────────────────────────────────────────

    private fun createSampleBook(id: String): BookEntity =
        BookEntity(
            id = id,
            title = "Book $id",
            author = "Author $id",
            coverPath = null,
            filePath = "/$id.epub",
            format = "epub",
            totalPages = 100,
            description = "Description for $id",
            updatedAtEpochMillis = 1000L,
            deletedAtEpochMillis = null,
            // Catalog upsert requires a remote file (Drive push sets this);
            // without it processBookItem defers the row as local-only.
            remotePath = "books/user-1/$id.epub",
        )

    // ─── Fake DAOs (inline, same pattern as LibraryRepositoryImplTest) ─

    private class FakeBookDao : BookDao {
        private val booksState = MutableStateFlow<List<BookEntity>>(emptyList())

        override fun observeAllBooks(): Flow<List<BookEntity>> = booksState.map { books -> books.filter { it.deletedAtEpochMillis == null } }

        override fun observeReadingBooks(): Flow<List<BookEntity>> =
            booksState.map { books ->
                books.filter { it.deletedAtEpochMillis == null && it.readingState == "reading" && it.progressPercentage < 100f }
            }

        override suspend fun upsert(book: BookEntity) {
            booksState.value =
                booksState.value
                    .filterNot { it.id == book.id }
                    .plus(book)
        }

        override suspend fun upsertAll(books: List<BookEntity>) {
            books.forEach { upsert(it) }
        }

        override fun observeBookById(bookId: String): Flow<BookEntity?> = MutableStateFlow(booksState.value.firstOrNull { it.id == bookId })

        override suspend fun getBookById(bookId: String): BookEntity? = booksState.value.firstOrNull { it.id == bookId }

        override suspend fun deleteBook(
            bookId: String,
            deletedAt: Long,
        ) {
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) {
                        book.copy(
                            updatedAtEpochMillis = deletedAt,
                            deletedAtEpochMillis = deletedAt,
                        )
                    } else {
                        book
                    }
                }
        }

        override suspend fun deleteById(bookId: String) {
            booksState.value = booksState.value.filterNot { it.id == bookId }
        }

        override suspend fun updateRating(
            bookId: String,
            rating: Int?,
        ) {
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) book.copy(userRating = rating) else book
                }
        }

        override suspend fun updateStatus(
            bookId: String,
            status: String?,
            updatedAt: Long,
        ) {
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) book.copy(status = status, updatedAtEpochMillis = updatedAt) else book
                }
        }

        override suspend fun startReading(
            bookId: String,
            updatedAt: Long,
        ) {}

        override suspend fun updateReadingProgress(
            bookId: String,
            progress: Float,
            updatedAt: Long,
        ) {}

        override suspend fun completeReading(
            bookId: String,
            updatedAt: Long,
        ) {}

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
            booksState.value =
                booksState.value.map { book ->
                    if (book.id == bookId) {
                        book.copy(
                            title = title,
                            author = author,
                            description = description,
                            coverPath = coverPath,
                            updatedAtEpochMillis = updatedAt,
                        )
                    } else {
                        book
                    }
                }
        }

        override suspend fun count(): Int = booksState.value.size

        override fun observeAllBooksPaged(): androidx.paging.PagingSource<Int, BookEntity> = com.nexo.testutil.FakePagingSource(emptyList())
    }

    private class FakeSyncOutboxDao : SyncOutboxDao {
        private val items = mutableListOf<SyncOutboxEntity>()

        override suspend fun getPendingItems(): List<SyncOutboxEntity> = items.toList().sortedBy { it.createdAtEpochMillis }

        override suspend fun insert(item: SyncOutboxEntity) {
            items.add(item)
        }

        override suspend fun deleteById(id: String) {
            items.removeAll { it.id == id }
        }

        override suspend fun incrementRetryCount(
            id: String,
            error: String,
        ) {
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) {
                items[index] =
                    items[index].copy(
                        retryCount = items[index].retryCount + 1,
                        lastError = error,
                    )
            }
        }

        override suspend fun pruneFailedItems(maxRetries: Int) {
            items.removeAll { it.retryCount >= maxRetries }
        }

        override fun observePendingCount(): Flow<Int> = MutableStateFlow(items.size)

        override suspend fun getByTypeAndEntityId(
            type: String,
            entityId: String,
        ): SyncOutboxEntity? = items.firstOrNull { it.entityType == type && it.entityId == entityId }

        override suspend fun updatePayload(
            id: String,
            payloadJson: String,
        ) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) items[idx] = items[idx].copy(payloadJson = payloadJson)
        }

        override suspend fun upsertCoalesced(item: SyncOutboxEntity) {
            val existing = item.entityId?.let { getByTypeAndEntityId(item.entityType, it) }
            if (existing != null) updatePayload(existing.id, item.payloadJson) else items.add(item)
        }
    }
}
