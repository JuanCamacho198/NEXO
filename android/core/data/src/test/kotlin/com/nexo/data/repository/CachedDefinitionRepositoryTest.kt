package com.nexo.data.repository

import com.nexo.data.local.DefinitionCacheKey
import com.nexo.data.local.dao.DefinitionCacheDao
import com.nexo.data.local.entity.DefinitionCacheEntity
import com.nexo.domain.definition.DefinitionErrorCode
import com.nexo.domain.definition.DefinitionFailure
import com.nexo.domain.definition.DefinitionItem
import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.DefinitionSource
import com.nexo.domain.definition.WordLanguage
import com.nexo.domain.repository.DefinitionRepository
import com.nexo.domain.session.ActiveUserProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedDefinitionRepositoryTest {
    private class FakeDao : DefinitionCacheDao {
        val rows = mutableMapOf<String, DefinitionCacheEntity>()
        var puts = 0

        override suspend fun get(
            userId: String,
            key: String,
        ): DefinitionCacheEntity? = rows["$userId::$key"]

        override suspend fun put(entry: DefinitionCacheEntity) {
            puts++
            rows["${entry.userId}::${entry.key}"] = entry
        }

        override suspend fun deleteOldestBeyond(
            userId: String,
            keep: Int,
        ): Int {
            val owned = rows.values.filter { it.userId == userId }.sortedByDescending { it.createdAtEpochMillis }
            val doomed = owned.drop(keep)
            rows.entries.removeAll { (_, row) -> doomed.any { it.key == row.key } }
            return doomed.size
        }

        override suspend fun count(userId: String): Int = rows.values.count { it.userId == userId }

        override suspend fun deleteAll(userId: String): Int {
            val before = rows.size
            rows.entries.removeAll { (_, row) -> row.userId == userId }
            return before - rows.size
        }
    }

    /** A delegate counting calls and returning a scripted result. */
    private class FakeDelegate(
        var result: DefinitionResult,
    ) : DefinitionRepository {
        var calls = 0
        var lastRequest: DefinitionRequest? = null

        override suspend fun define(request: DefinitionRequest): DefinitionResult {
            calls++
            lastRequest = request
            return result
        }
    }

    private fun activeUser(userId: String? = "user-a"): ActiveUserProvider = ActiveUserProvider().apply { set(userId) }

    private fun request(
        word: String = "house",
        readerLanguage: String = "es",
        wordLanguage: String = "en",
    ) = DefinitionRequest(
        word = word,
        readerLanguage = readerLanguage,
        wordLanguage = WordLanguage.known(wordLanguage),
    )

    private fun result(
        word: String = "house",
        readerLanguage: String = "es",
        wordLanguage: String = "en",
    ) = DefinitionResult(
        word = word,
        definitions = listOf(DefinitionItem("casa", "sustantivo")),
        source = DefinitionSource.Native,
        wordLanguage = wordLanguage,
        readerLanguage = readerLanguage,
    )

    private fun kotlinx.coroutines.test.TestScope.repo(
        dao: FakeDao,
        delegate: FakeDelegate,
        activeUser: ActiveUserProvider = activeUser(),
    ) = CachedDefinitionRepository(
        delegate = delegate,
        cacheDao = dao,
        activeUser = activeUser,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    @Test
    fun `a second identical lookup touches no source`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            val repo = repo(dao, delegate)

            repo.define(request())
            val second = repo.define(request())

            assertEquals(1, delegate.calls)
            assertEquals(1, dao.puts)
            assertEquals(listOf(DefinitionItem("casa", "sustantivo")), second.definitions)
            assertEquals(DefinitionSource.Native, second.source)
        }

    @Test
    fun `the reader language participates in the key`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            val repo = repo(dao, delegate)

            repo.define(request(readerLanguage = "es"))
            repo.define(request(readerLanguage = "en"))

            // Two different reader languages are two different answers.
            assertEquals(2, delegate.calls)
            assertEquals(2, dao.puts)
        }

    @Test
    fun `the word language participates in the key`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            val repo = repo(dao, delegate)

            repo.define(request(wordLanguage = "en"))
            repo.define(request(wordLanguage = "fr"))

            assertEquals(2, delegate.calls)
        }

    @Test
    fun `an empty result is never cached`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(DefinitionResult.empty("zzz", "es", "en", true))
            val repo = repo(dao, delegate)

            repo.define(request(word = "zzz"))
            // A later request could succeed, so it must not be pinned.
            assertEquals(0, dao.puts)
        }

    @Test
    fun `a failure is never cached`() =
        runTest {
            val dao = FakeDao()
            val delegate =
                FakeDelegate(
                    DefinitionResult(
                        word = "house",
                        definitions = emptyList(),
                        source = null,
                        wordLanguage = "en",
                        readerLanguage = "es",
                        failure = DefinitionFailure(DefinitionErrorCode.NETWORK, "boom"),
                    ),
                )
            val repo = repo(dao, delegate)

            repo.define(request())

            assertEquals(0, dao.puts)
        }

    @Test
    fun `two accounts get their own rows`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            val userA = activeUser("user-a")
            val repoA = repo(dao, delegate, userA)
            repoA.define(request())

            // Account B must not see A's row.
            val repoB = repo(dao, delegate, activeUser("user-b"))
            repoB.define(request())

            assertEquals(2, delegate.calls)
            assertEquals(
                2,
                dao.rows.keys
                    .map { it.substringBefore("::") }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun `an anonymous reader neither reads nor writes`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            val repo = repo(dao, delegate, activeUser(null))

            repo.define(request())
            repo.define(request())

            // Both lookups reach the source (nothing is remembered they could
            // hit) and nothing is stored.
            assertEquals(2, delegate.calls)
            assertEquals(0, dao.puts)
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    fun `an anonymous reader cannot observe another account's cached lookup`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            repo(dao, delegate, activeUser("user-a")).define(request())

            val anonDaoCalls = repo(dao, delegate, activeUser(null)).define(request())

            // The anonymous lookup reached the source; it did not serve A's row.
            assertEquals(2, delegate.calls)
            assertEquals(listOf(DefinitionItem("casa", "sustantivo")), anonDaoCalls.definitions)
        }

    @Test
    fun `a corrupt payload reads as a miss and the cascade repairs it`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            val repo = repo(dao, delegate)
            val key = DefinitionCacheKey.of("user-a", "es", "en", "house")
            dao.rows["user-a::$key"] =
                DefinitionCacheEntity(
                    key = key,
                    userId = "user-a",
                    word = "house",
                    wordNormalized = "house",
                    wordLanguage = "en",
                    readerLanguage = "es",
                    payload = "{ not json",
                    createdAtEpochMillis = 0,
                )

            val result = repo.define(request())

            assertEquals(1, delegate.calls)
            assertEquals(DefinitionSource.Native, result.source)
        }

    @Test
    fun `re-caching the same key updates in place`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            val repo = repo(dao, delegate)

            repo.define(request())
            val key = DefinitionCacheKey.of("user-a", "es", "en", "house")
            // A second store of the same key REPLACEs the row rather than adding.
            val first = dao.rows["user-a::$key"]
            repo.define(request(readerLanguage = "es"))
            assertEquals(1, dao.rows.size)
            assertEquals(first?.key, dao.rows["user-a::$key"]?.key)
        }

    @Test
    fun `an anonymous prune is a no-op`() =
        runTest {
            val dao = FakeDao()
            val repo = repo(dao, FakeDelegate(result()), activeUser(null))
            assertEquals(0, repo.pruneCache())
        }

    @Test
    fun `a missing account resolves nothing and leaves the result intact`() =
        runTest {
            val dao = FakeDao()
            val delegate = FakeDelegate(result())
            val repo = repo(dao, delegate, activeUser(null))
            val outcome = repo.define(request())
            assertNull(outcome.failure)
            assertEquals(1, outcome.definitions.size)
        }
}
