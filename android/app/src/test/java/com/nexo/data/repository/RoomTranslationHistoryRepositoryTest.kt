package com.nexo.data.repository

import com.nexo.data.local.dao.TranslationCacheDao
import com.nexo.data.local.entity.TranslationCacheEntity
import com.nexo.domain.model.TranslationHistoryEntry
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins the account isolation and the decision-D5 rule of
 * [RoomTranslationHistoryRepository].
 *
 * `history()` and `clearHistory()` are the two places where a missing owner check
 * would silently reach every account's rows: `history()` would show account B what
 * account A looked up, and `clearHistory()` — the privacy control — would delete
 * another account's log. Both guards are `?: return` expressions.
 *
 * ## Why the row-level assertions are not enough
 *
 * Row assertions alone cannot tell a working guard from a deleted one. A broken
 * `clearHistory` guard evaluates to the empty string, and every method here
 * filters by `user_id`, so `deleteAll("")` deletes nothing and every count stays
 * exactly as a working guard would leave it — the suite stayed green with the
 * guard removed. The guard is only observable when the DAO call itself is: the
 * `ownersDeletedFrom`/`historyQueries` recorders below pin the argument, not just
 * the survivor rows.
 */
class RoomTranslationHistoryRepositoryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeDao : TranslationCacheDao {
        val rows = mutableMapOf<String, TranslationCacheEntity>()

        /** Every `userId` passed to [deleteAll], in call order. Empty means it was never called. */
        val ownersDeletedFrom = mutableListOf<String>()

        /** Every `userId` passed to [history], in call order. Empty means the DAO was never queried. */
        val historyQueries = mutableListOf<String>()

        override suspend fun get(
            userId: String,
            langPair: String,
            wordNormalized: String,
        ): TranslationCacheEntity? = rows["$userId::$langPair::$wordNormalized"]

        override suspend fun put(entry: TranslationCacheEntity) {
            rows["${entry.userId}::${entry.langPair}::${entry.wordNormalized}"] = entry
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

        override fun history(userId: String): Flow<List<TranslationCacheEntity>> {
            historyQueries += userId
            return flowOf(rows.values.filter { it.userId == userId })
        }

        override suspend fun deleteAll(userId: String): Int {
            ownersDeletedFrom += userId
            val before = rows.size
            rows.entries.removeAll { (_, row) -> row.userId == userId }
            return before - rows.size
        }
    }

    private fun activeUser(userId: String? = "user-a"): ActiveUserProvider = ActiveUserProvider().apply { set(userId) }

    private fun repo(activeUser: ActiveUserProvider = activeUser()) =
        RoomTranslationHistoryRepository(
            cacheDao = FakeDao(),
            activeUser = activeUser,
        )

    /**
     * Seeds [count] rows owned by [userId].
     *
     * The map key carries the account, or seeding B would overwrite A's rows and
     * the isolation assertions would pass for the wrong reason.
     */
    private fun seedRows(
        dao: FakeDao,
        userId: String,
        count: Int,
    ) {
        repeat(count) { index ->
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

    // ── H1: history() reads only the active account ──────────────────

    @Test
    fun `history returns the active account's own rows`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            val entries = RoomTranslationHistoryRepository(dao, activeUser("user-a")).history().first()
            assertEquals(
                "the active account's rows must be present, or an empty flow would look like isolation",
                3,
                entries.size,
            )
            assertEquals(
                setOf("user-a-seed-0", "user-a-seed-1", "user-a-seed-2"),
                entries.map { it.id }.toSet(),
            )
        }

    @Test
    fun `history never includes another account's rows`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            seedRows(dao, "user-b", 5)
            val entries = RoomTranslationHistoryRepository(dao, activeUser("user-a")).history().first()
            assertEquals(3, entries.size)
            assertTrue(
                "account B's rows leaked into account A's history",
                entries.none { it.id.startsWith("user-b-") },
            )
        }

    // ── H2: clearHistory() deletes only the active account ───────────

    @Test
    fun `clearHistory deletes the active account's rows and leaves the other account untouched`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 4)
            seedRows(dao, "user-b", 6)
            RoomTranslationHistoryRepository(dao, activeUser("user-a")).clearHistory()

            assertEquals(0, dao.count("user-a"))
            assertEquals(
                "account B's rows are not account A's to delete",
                6,
                dao.count("user-b"),
            )
            // Concretely, not only by count: a count that matches for the wrong
            // reason (rows replaced instead of kept) must still fail here.
            val survivor = dao.rows["user-b::es|en::user-b-seed-3"]
            assertTrue("account B's seed row must survive account A's clear", survivor != null)
            assertEquals("user-b-seed-3", survivor?.id)
            assertEquals("user-b", survivor?.userId)
        }

    @Test
    fun `clearHistory leaves the other account's rows readable`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 4)
            seedRows(dao, "user-b", 6)
            RoomTranslationHistoryRepository(dao, activeUser("user-a")).clearHistory()

            val bEntries = RoomTranslationHistoryRepository(dao, activeUser("user-b")).history().first()
            assertEquals(6, bEntries.size)
            assertFalse(
                "account B's history must not be emptied by account A's clear",
                bEntries.isEmpty(),
            )
        }

    // ── H3: decision D5 — no active account writes or reads nothing ──

    @Test
    fun `history is empty without an active account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            val entries = RoomTranslationHistoryRepository(dao, activeUser(null)).history().first()
            assertEquals(
                "an ownerless read must not fall back to every account's rows",
                emptyList<TranslationHistoryEntry>(),
                entries,
            )
        }

    @Test
    fun `clearHistory deletes nothing without an active account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 7)
            val before = dao.count("user-a")
            RoomTranslationHistoryRepository(dao, activeUser(null)).clearHistory()
            assertEquals(
                "the anonymous path owns no history, so it must not delete any",
                before,
                dao.count("user-a"),
            )
            assertEquals(7, dao.count("user-a"))
        }

    // ── H4: the owner guard is observable at the DAO boundary ────────

    @Test
    fun `clearHistory hands the active account id to the dao, never an empty owner`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 4)
            RoomTranslationHistoryRepository(dao, activeUser("user-a")).clearHistory()

            assertEquals(
                "clearHistory must reach the dao exactly once for the active account",
                listOf("user-a"),
                dao.ownersDeletedFrom,
            )
            assertTrue(
                "the owner handed to deleteAll must never be empty: an empty owner is the " +
                    "removed-guard signature, and it stays invisible because it deletes nothing",
                dao.ownersDeletedFrom.none { it.isEmpty() },
            )
        }

    @Test
    fun `clearHistory never reaches the dao without an active account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 7)
            RoomTranslationHistoryRepository(dao, activeUser(null)).clearHistory()

            assertEquals(
                "the ownerless guard must return before deleteAll, so the dao sees no call at all",
                emptyList<String>(),
                dao.ownersDeletedFrom,
            )
        }

    @Test
    fun `history queries the dao for the active account, never an empty owner`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            RoomTranslationHistoryRepository(dao, activeUser("user-a")).history().first()

            assertEquals(
                "history must query the dao exactly once for the active account",
                listOf("user-a"),
                dao.historyQueries,
            )
            assertTrue(
                "the owner handed to history must never be empty: an empty owner is the " +
                    "removed-guard signature, and it returns no rows so the flow looks correct",
                dao.historyQueries.none { it.isEmpty() },
            )
        }

    @Test
    fun `history never reaches the dao without an active account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            RoomTranslationHistoryRepository(dao, activeUser(null)).history().first()

            assertEquals(
                "the ownerless guard must return before the query, so the dao sees no call at all",
                emptyList<String>(),
                dao.historyQueries,
            )
        }
}
