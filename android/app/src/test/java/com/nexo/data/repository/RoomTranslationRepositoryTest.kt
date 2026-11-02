package com.nexo.data.repository

import com.nexo.data.local.dao.TranslationCacheDao
import com.nexo.data.local.entity.TranslationCacheEntity
import com.nexo.data.remote.translation.MyMemoryTranslationClient
import com.nexo.data.remote.translation.TranslationHttpResponse
import com.nexo.data.remote.translation.TranslationHttpTransport
import com.nexo.domain.translation.TranslationOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTranslationRepositoryTest {
    private class FakeDao : TranslationCacheDao {
        val rows = mutableMapOf<String, TranslationCacheEntity>()
        var puts = 0

        override suspend fun get(
            langPair: String,
            wordNormalized: String,
        ): TranslationCacheEntity? = rows["$langPair::$wordNormalized"]

        override suspend fun put(entry: TranslationCacheEntity) {
            puts++
            rows["${entry.langPair}::${entry.wordNormalized}"] = entry
        }

        override suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int = 0

        override suspend fun count(): Int = rows.size
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

    private fun kotlinx.coroutines.test.TestScope.repo(
        dao: FakeDao,
        transport: FakeTransport,
    ) = RoomTranslationRepository(
        client = MyMemoryTranslationClient(transport),
        cacheDao = dao,
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
            dao.rows["es|en::frances"] =
                TranslationCacheEntity(
                    id = "1",
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
}
