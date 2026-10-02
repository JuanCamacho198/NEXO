package com.nexo.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.local.AppDatabase
import com.nexo.data.local.DictionaryNormalizer
import com.nexo.data.local.dao.DictionaryWordDao
import com.nexo.data.local.entity.DictionaryWordEntity
import com.nexo.data.session.SessionManager
import com.nexo.domain.model.AuthSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * FR-09: Android push/pull against the `user_dictionary_words` contract.
 *
 * Local storage is a real in-memory Room database; the remote is an in-memory
 * fake that implements the PostgREST semantics the contract depends on
 * (server-generated id, `(user_id, normalized_word)` conflict target,
 * soft-delete by natural key). What is mocked: the network/PostgREST transport.
 * What is live: the sync service, the local Room schema, and the LWW merge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictionarySyncServiceTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: DictionaryWordDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.dictionaryWordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun service(
        remote: DictionaryRemoteDataSource,
        session: SessionManager = liveSession(),
        now: Long = 1_700_000_000_000L,
    ) = DictionarySyncService(dao = dao, sessionManager = session, remote = remote, nowMillis = { now })

    private fun entity(
        id: String = "local-1",
        word: String = "Abyss",
        addedAt: Long = 1_000L,
        updatedAt: Long = addedAt,
        definition: String? = null,
    ) = DictionaryWordEntity(
        id = id,
        word = word,
        addedAtEpochMillis = addedAt,
        updatedAtEpochMillis = updatedAt,
        definition = definition,
        partOfSpeech = "noun",
        phonetic = "/abc/",
        example = "example",
        quote = "quote",
        sourceBookId = "book-1",
        sourceBookTitle = "Title",
        sourceBookAuthor = "Author",
        sourceChapter = "Ch1",
        sourceLocator = "epubcfi(/6/4)",
    )

    private fun remoteRow(
        id: String = "server-1",
        word: String = "Abyss",
        normalized: String = "abyss",
        updatedAtMillis: Long = 2_000L,
        deletedAtMillis: Long? = null,
    ) = DictionaryWordRemoteRow(
        id = id,
        userId = USER,
        word = word,
        normalizedWord = normalized,
        updatedAt = Instant.ofEpochMilli(updatedAtMillis).toString(),
        deletedAt = deletedAtMillis?.let { Instant.ofEpochMilli(it).toString() },
        definition = "def",
    )

    // ─── Push: natural-key contract ──────────────────────────────────────

    @Test
    fun `push uses the natural key and never sends the local id`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            service(remote).pushWord(entity(id = "local-1", word = "Abyss"))

            val stored = remote.store.values.single()
            assertEquals("abyss", stored.normalizedWord)
            assertEquals("server-1", stored.id)
            assertNotEquals("local-1", stored.id)
            assertEquals("Abyss", stored.word)
        }

    @Test
    fun `re-push of the same normalized word from another local id keeps one row and the server id`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            val sync = service(remote)
            sync.pushWord(entity(id = "local-1", word = "Abyss"))
            sync.pushWord(entity(id = "local-2", word = "abyss"))

            assertEquals(1, remote.store.size)
            assertEquals(
                "server-1",
                remote.store.values
                    .single()
                    .id,
            )
        }

    @Test
    fun `push sends every Android-owned evidence column with explicit nulls`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            service(remote).pushWord(entity(word = "Abyss", definition = null))
            val push = remote.lastPush

            assertEquals(USER, push?.userId)
            assertEquals("abyss", push?.normalizedWord)
            assertEquals(Instant.ofEpochMilli(1_000L).toString(), push?.updatedAt)
            assertNull(push?.deletedAt)
            // Android-owned evidence columns are present on the payload, even null.
            assertNull(push?.definition)
            assertEquals("noun", push?.partOfSpeech)
            assertEquals("/abc/", push?.phonetic)
            assertEquals("example", push?.example)
            assertEquals("quote", push?.quote)
            assertEquals("book-1", push?.sourceBookId)
            assertEquals("Title", push?.sourceBookTitle)
            assertEquals("Author", push?.sourceBookAuthor)
            assertEquals("Ch1", push?.sourceChapter)
            assertEquals("epubcfi(/6/4)", push?.sourceLocator)
        }

    @Test
    fun `push is gated without a session and never touches the remote`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            val result = service(remote, session = gatedSession()).pushWord(entity())

            assertFalse(result)
            assertTrue(remote.store.isEmpty())
        }

    // ─── Delete: soft-delete by natural key ──────────────────────────────

    @Test
    fun `delete soft-deletes by normalized word and never by id`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            val sync = service(remote)
            sync.pushWord(entity(word = "Abyss"))
            val pushedAt =
                remote.store.values
                    .single()
                    .updatedAt

            assertTrue(sync.deleteWord("Abyss"))

            val stored = remote.store.values.single()
            assertEquals(listOf(USER to "abyss"), remote.deleteCalls)
            assertTrue(stored.deletedAt != null)
            assertTrue(stored.updatedAt > pushedAt)
        }

    // ─── Pull: reinstall restore + LWW ───────────────────────────────────

    @Test
    fun `pull with empty local restores every remote entry (reinstall)`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            remote.store[key(USER, "abyss")] = remoteRow(id = "server-1", word = "Abyss", normalized = "abyss")
            remote.store[key(USER, "lumen")] =
                remoteRow(id = "server-2", word = "Lumen", normalized = "lumen", updatedAtMillis = 3_000L)

            assertEquals(2, service(remote).pullRemote())
            assertEquals(2, dao.getAll().size)
            assertEquals(setOf("Abyss", "Lumen"), dao.getAll().map { it.word }.toSet())
        }

    @Test
    fun `android to desktop round trip preserves identical content`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            val original = entity(word = "Efímero", definition = "Que dura poco")
            service(remote).pushWord(original)

            // Read the remote row exactly as desktop's SupabaseDictionarySync reads it.
            val desktopRow = remote.store.values.single()
            assertEquals(original.word, desktopRow.word)
            assertEquals(DictionaryNormalizer.normalize(original.word), desktopRow.normalizedWord)
            assertEquals(original.definition, desktopRow.definition)
            assertEquals(original.partOfSpeech, desktopRow.partOfSpeech)
            assertEquals(original.phonetic, desktopRow.phonetic)
            assertEquals(original.example, desktopRow.example)
            assertEquals(original.quote, desktopRow.quote)
            assertEquals(original.sourceBookId, desktopRow.sourceBookId)
            assertEquals(original.sourceLocator, desktopRow.sourceLocator)
        }

    @Test
    fun `pull applies remote content when remote is newer`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            dao.insert(entity(id = "local-1", word = "Local", addedAt = 1_000L))
            remote.store[key(USER, "local")] =
                remoteRow(id = "server-9", word = "Remote", normalized = "local", updatedAtMillis = 5_000L)

            service(remote).pullRemote()

            val stored = dao.getAll().single()
            assertEquals("Remote", stored.word)
            // The write clock advances to the remote time; the local "first added"
            // time is preserved because a remote update does not re-add the word.
            assertEquals(5_000L, stored.updatedAtEpochMillis)
            assertEquals(1_000L, stored.addedAtEpochMillis)
        }

    @Test
    fun `pull does not overwrite a newer local edit`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            dao.insert(entity(id = "local-1", word = "Local", addedAt = 9_000L))
            remote.store[key(USER, "local")] =
                remoteRow(id = "server-9", word = "Remote", normalized = "local", updatedAtMillis = 1_000L)

            service(remote).pullRemote()

            assertEquals("Local", dao.getAll().single().word)
        }

    @Test
    fun `pull removes the local row when the remote tombstone is newer`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            dao.insert(entity(id = "local-1", word = "Local", addedAt = 1_000L))
            remote.store[key(USER, "local")] =
                remoteRow(
                    id = "server-9",
                    word = "Local",
                    normalized = "local",
                    updatedAtMillis = 5_000L,
                    deletedAtMillis = 5_000L,
                )

            service(remote).pullRemote()

            assertTrue(dao.getAll().isEmpty())
        }

    @Test
    fun `pull keeps the local row when the tombstone is older than the local edit`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            dao.insert(entity(id = "local-1", word = "Local", addedAt = 9_000L))
            remote.store[key(USER, "local")] =
                remoteRow(
                    id = "server-9",
                    word = "Local",
                    normalized = "local",
                    updatedAtMillis = 1_000L,
                    deletedAtMillis = 1_000L,
                )

            service(remote).pullRemote()

            assertEquals("Local", dao.getAll().single().word)
        }

    @Test
    fun `pull is gated without a session`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            remote.store[key(USER, "abyss")] = remoteRow()

            assertEquals(0, service(remote, session = gatedSession()).pullRemote())
        }

    // ─── Regression: offline edit vs stale remote (LWW write clock) ──────

    @Test
    fun `offline local edit survives a stale remote merge`() =
        runBlocking {
            val remote = FakeDictionaryRemote()
            // Word added at t=1000, then edited locally at t=9000 while offline
            // (push failed); updatedAtEpochMillis advanced, addedAt unchanged.
            dao.insert(
                entity(
                    id = "local-1",
                    word = "Local",
                    addedAt = 1_000L,
                    updatedAt = 9_000L,
                    definition = "edited offline",
                ),
            )
            // A concurrent, OLDER desktop row (t=5000) that predates the edit.
            remote.store[key(USER, "local")] =
                remoteRow(id = "server-9", word = "Local", normalized = "local", updatedAtMillis = 5_000L)

            service(remote).pullRemote()

            val stored = dao.getAll().single()
            assertEquals("edited offline", stored.definition)
            assertEquals(9_000L, stored.updatedAtEpochMillis)
            assertEquals(1_000L, stored.addedAtEpochMillis)
        }

    @Test
    fun `user field edit advances the sync version and preserves the added time`() =
        runBlocking {
            dao.insert(entity(id = "local-1", word = "Local", addedAt = 1_000L, updatedAt = 1_000L))

            dao.updateUserFields(
                wordId = "local-1",
                definition = "edited",
                partOfSpeech = "noun",
                phonetic = null,
                example = null,
                updatedAtEpochMillis = 9_000L,
            )

            val stored = dao.findById("local-1")!!
            assertEquals("edited", stored.definition)
            assertEquals(9_000L, stored.updatedAtEpochMillis)
            assertEquals(1_000L, stored.addedAtEpochMillis)
        }

    // ─── Fakes ───────────────────────────────────────────────────────────

    private fun liveSession(): SessionManager =
        object : SessionManager {
            override suspend fun restoreSession() = Result.success(session())

            override suspend fun getCurrentSession() = Result.success(session())

            override suspend fun ensureFreshSession() = Result.success(session())

            override suspend fun signOutAll() = Result.success(Unit)

            override suspend fun setCurrentSession(session: AuthSession?) = Result.success(Unit)
        }

    private fun gatedSession(): SessionManager =
        object : SessionManager {
            override suspend fun restoreSession() = Result.success(null)

            override suspend fun getCurrentSession() = Result.success(null)

            override suspend fun ensureFreshSession() = Result.failure<AuthSession>(IllegalStateException("no session"))

            override suspend fun signOutAll() = Result.success(Unit)

            override suspend fun setCurrentSession(session: AuthSession?) = Result.success(Unit)
        }

    private fun session() = AuthSession(userId = USER, email = "test@example.com")

    private class FakeDictionaryRemote : DictionaryRemoteDataSource {
        val store = LinkedHashMap<String, DictionaryWordRemoteRow>()
        val deleteCalls = mutableListOf<Pair<String, String>>()
        var lastPush: DictionaryWordPushRow? = null
        private var seq = 0

        override suspend fun upsert(row: DictionaryWordPushRow) {
            lastPush = row
            val existing = store[key(row.userId, row.normalizedWord)]
            store[key(row.userId, row.normalizedWord)] =
                DictionaryWordRemoteRow(
                    id = existing?.id ?: "server-${++seq}",
                    userId = row.userId,
                    word = row.word,
                    normalizedWord = row.normalizedWord,
                    updatedAt = row.updatedAt,
                    deletedAt = row.deletedAt,
                    definition = row.definition,
                    partOfSpeech = row.partOfSpeech,
                    phonetic = row.phonetic,
                    example = row.example,
                    quote = row.quote,
                    sourceBookId = row.sourceBookId,
                    sourceBookTitle = row.sourceBookTitle,
                    sourceBookAuthor = row.sourceBookAuthor,
                    sourceChapter = row.sourceChapter,
                    sourceLocator = row.sourceLocator,
                )
        }

        override suspend fun softDelete(
            userId: String,
            normalizedWord: String,
        ) {
            deleteCalls += userId to normalizedWord
            val existing = store[key(userId, normalizedWord)] ?: return
            val now = Instant.now().toString()
            store[key(userId, normalizedWord)] = existing.copy(deletedAt = now, updatedAt = now)
        }

        override suspend fun listChanges(
            userId: String,
            updatedAfterIso: String?,
        ): List<DictionaryWordRemoteRow> =
            store.values.filter {
                it.userId == userId && (updatedAfterIso == null || it.updatedAt > updatedAfterIso)
            }
    }

    private companion object {
        const val USER = "test-user"

        fun key(
            userId: String,
            normalized: String,
        ) = "$userId|$normalized"
    }
}
