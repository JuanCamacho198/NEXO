package com.nexo.data.repository

import com.nexo.data.local.dao.TranslationCacheDao
import com.nexo.data.local.entity.TranslationCacheEntity
import com.nexo.data.remote.translation.MyMemoryTranslationClient
import com.nexo.data.remote.translation.TranslationHttpResponse
import com.nexo.data.remote.translation.TranslationHttpTransport
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.domain.translation.TranslationOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTranslationRepositoryTest {
    private class FakeDao : TranslationCacheDao {
        val rows = mutableMapOf<String, TranslationCacheEntity>()
        var puts = 0

        override suspend fun get(
            userId: String,
            langPair: String,
            wordNormalized: String,
        ): TranslationCacheEntity? = rows["$userId::$langPair::$wordNormalized"]

        override suspend fun put(entry: TranslationCacheEntity) {
            puts++
            rows["${entry.userId}::${entry.langPair}::${entry.wordNormalized}"] = entry
        }

        override suspend fun deleteOlderThan(
            userId: String,
            cutoffEpochMillis: Long,
        ): Int {
            val before = rows.size
            rows.entries.removeAll { (_, row) ->
                row.userId == userId && row.createdAtEpochMillis < cutoffEpochMillis
            }
            return before - rows.size
        }

        override suspend fun deleteOldestBeyond(
            userId: String,
            keep: Int,
        ): Int {
            val owned = rows.values.filter { it.userId == userId }.sortedByDescending { it.createdAtEpochMillis }
            val doomed = owned.drop(keep)
            rows.entries.removeAll { (_, row) -> doomed.any { it.id == row.id } }
            return doomed.size
        }

        override suspend fun count(userId: String): Int = rows.values.count { it.userId == userId }

        override fun history(userId: String): Flow<List<TranslationCacheEntity>> = flowOf(rows.values.filter { it.userId == userId })

        override suspend fun deleteAll(userId: String): Int {
            val before = rows.size
            rows.entries.removeAll { (_, row) -> row.userId == userId }
            return before - rows.size
        }
    }

    private class FakeTransport(
        var response: TranslationHttpResponse =
            TranslationHttpResponse(
                200,
                """{"responseData":{"translatedText":"French"},"responseStatus":200}""",
            ),
    ) : TranslationHttpTransport {
        var calls = 0

        override suspend fun get(url: String): TranslationHttpResponse {
            calls++
            return response
        }
    }

    private fun activeUser(userId: String? = "user-a"): ActiveUserProvider = ActiveUserProvider().apply { set(userId) }

    private fun kotlinx.coroutines.test.TestScope.repo(
        dao: FakeDao,
        transport: FakeTransport,
        activeUser: ActiveUserProvider = activeUser(),
    ) = RoomTranslationRepository(
        client = MyMemoryTranslationClient(transport),
        cacheDao = dao,
        activeUser = activeUser,
        ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `translate queries the network on a cache miss and stores the result`() =
        runTest {
            val dao = FakeDao()
            val transport = FakeTransport()
            val outcome = repo(dao, transport).translate("francés", "es", "en")
            assertEquals(TranslationOutcome.Translated("French", fromCache = false), outcome)
            assertEquals(1, transport.calls)
            assertEquals(1, dao.puts)
        }

    @Test
    fun `translate serves a cache hit without touching the network`() =
        runTest {
            val dao = FakeDao()
            dao.rows["user-a::es|en::frances"] =
                TranslationCacheEntity(
                    id = "1",
                    userId = "user-a",
                    originalText = "francés",
                    wordNormalized = "frances",
                    langPair = "es|en",
                    translatedText = "French",
                    createdAtEpochMillis = 0,
                )
            val transport = FakeTransport()
            val outcome = repo(dao, transport).translate("francés", "es", "en")
            assertEquals(TranslationOutcome.Translated("French", fromCache = true), outcome)
            assertEquals(0, transport.calls)
        }

    @Test
    fun `translate reports NoTranslation when the API echoes the source`() =
        runTest {
            val dao = FakeDao()
            val transport =
                FakeTransport(
                    TranslationHttpResponse(
                        200,
                        """{"responseData":{"translatedText":"francés","match":0.99},"responseStatus":200}""",
                    ),
                )
            val outcome = repo(dao, transport).translate("francés", "es", "en")
            assertEquals(TranslationOutcome.NoTranslation, outcome)
            assertEquals(0, dao.puts)
        }

    @Test
    fun `translate reports Offline when the network fails`() =
        runTest {
            val dao = FakeDao()
            val transport =
                object : TranslationHttpTransport {
                    override suspend fun get(url: String): TranslationHttpResponse = throw java.io.IOException("down")
                }
            val repo =
                RoomTranslationRepository(
                    client = MyMemoryTranslationClient(transport),
                    cacheDao = dao,
                    activeUser = activeUser(),
                    ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
                )
            assertEquals(TranslationOutcome.Offline, repo.translate("hola", "es", "en"))
        }

    @Test
    fun `translate with allowNetwork false is cache-only`() =
        runTest {
            val dao = FakeDao()
            val transport = FakeTransport()
            val outcome = repo(dao, transport).translate("hola", "es", "en", allowNetwork = false)
            assertEquals(TranslationOutcome.Offline, outcome)
            assertEquals(0, transport.calls)
        }

    @Test
    fun `translate reuses the cache across case differences`() =
        runTest {
            val dao = FakeDao()
            val transport = FakeTransport()
            val repository = repo(dao, transport)
            repository.translate("Francés", "es", "en")
            val second = repository.translate("francés", "es", "en")
            assertTrue(second is TranslationOutcome.Translated)
            assertEquals(1, transport.calls)
        }

    // ── Account isolation (T3) ───────────────────────────────────────

    @Test
    fun `account B never receives account A's cached translation`() =
        runTest {
            val dao = FakeDao()
            val transport = FakeTransport()

            // Account A translates and caches.
            repo(dao, transport, activeUser("user-a")).translate("reverberación", "es", "en")
            assertEquals(1, transport.calls)
            assertEquals(1, dao.puts)

            // Account B selects the same word: it must NOT read A's row.
            val second = FakeTransport()
            val outcome = repo(dao, second, activeUser("user-b")).translate("reverberación", "es", "en")
            assertTrue(outcome is TranslationOutcome.Translated)
            assertEquals("account B must hit the network, not A's cache", 1, second.calls)
            assertEquals(2, dao.puts)
        }

    @Test
    fun `rows are stored under the active account id`() =
        runTest {
            val dao = FakeDao()
            repo(dao, FakeTransport(), activeUser("user-a")).translate("hola", "es", "en")
            assertEquals(1, dao.count("user-a"))
            assertEquals(0, dao.count("user-b"))
        }

    // ── Anonymous path (decision D5) ─────────────────────────────────

    @Test
    fun `the anonymous path translates but persists nothing`() =
        runTest {
            val dao = FakeDao()
            val transport = FakeTransport()
            val outcome = repo(dao, transport, activeUser(null)).translate("hola", "es", "en")
            assertEquals(TranslationOutcome.Translated("French", fromCache = false), outcome)
            assertEquals(1, transport.calls)
            assertEquals(0, dao.puts)
        }

    // ── Cache pruning (T4 of `translation-guards-and-cache-pruning`) ──
    //
    // The cache is capped per account by row count, not by age: a row limit is
    // predictable on disk, and a day-based cutoff punishes the reader who is
    // occasionally heavy. The sweep runs at a session boundary (see
    // `TranslationManager.onBookOpened`), never per translation, so no query
    // pays an extra write.

    private fun seedRows(
        dao: FakeDao,
        userId: String,
        count: Int,
    ) {
        repeat(count) { index ->
            // The key must carry the account, or seeding B would overwrite A's
            // rows and the isolation tests would pass for the wrong reason.
            val id = "$userId-seed-$index"
            dao.rows["$userId::es|en::$id"] =
                TranslationCacheEntity(
                    id = id,
                    userId = userId,
                    originalText = "palabra$index",
                    wordNormalized = id,
                    langPair = "es|en",
                    translatedText = "word$index",
                    createdAtEpochMillis = index.toLong(),
                )
        }
    }

    @Test
    fun `pruneCache deletes the oldest rows once the account is over the cap`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", RoomTranslationRepository.MAX_CACHED_TRANSLATIONS_PER_USER + 10)
            val deleted = repo(dao, FakeTransport()).pruneCache()
            assertEquals(10, deleted)
            assertEquals(
                RoomTranslationRepository.MAX_CACHED_TRANSLATIONS_PER_USER,
                dao.count("user-a"),
            )
        }

    @Test
    fun `pruneCache keeps the newest rows`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", RoomTranslationRepository.MAX_CACHED_TRANSLATIONS_PER_USER + 1)
            repo(dao, FakeTransport()).pruneCache()
            val survivingNewest = dao.rows.values.maxByOrNull { it.createdAtEpochMillis }
            assertEquals(
                "the newest row must survive the sweep",
                RoomTranslationRepository.MAX_CACHED_TRANSLATIONS_PER_USER.toLong(),
                survivingNewest?.createdAtEpochMillis,
            )
        }

    @Test
    fun `pruneCache does nothing while the account is under the cap`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 5)
            assertEquals(0, repo(dao, FakeTransport()).pruneCache())
            assertEquals(5, dao.count("user-a"))
        }

    @Test
    fun `pruneCache never touches another account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", RoomTranslationRepository.MAX_CACHED_TRANSLATIONS_PER_USER + 10)
            seedRows(dao, "user-b", 7)
            repo(dao, FakeTransport(), activeUser("user-a")).pruneCache()
            assertEquals(
                "account B's rows are not account A's to prune",
                7,
                dao.count("user-b"),
            )
        }

    @Test
    fun `pruneCache is a no-op for the anonymous path`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", RoomTranslationRepository.MAX_CACHED_TRANSLATIONS_PER_USER + 10)
            assertEquals(0, repo(dao, FakeTransport(), activeUser(null)).pruneCache())
            assertEquals(
                "an anonymous session owns no cache",
                RoomTranslationRepository.MAX_CACHED_TRANSLATIONS_PER_USER + 10,
                dao.count("user-a"),
            )
        }
}
