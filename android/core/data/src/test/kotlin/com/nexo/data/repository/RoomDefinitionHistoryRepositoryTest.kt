package com.nexo.data.repository

import com.nexo.data.local.dao.DefinitionCacheDao
import com.nexo.data.local.entity.DefinitionCacheEntity
import com.nexo.domain.definition.DefinitionItem
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.DefinitionSource
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
 * Pins the account isolation, the decision-D5 rule and the decode tolerance of
 * [RoomDefinitionHistoryRepository].
 *
 * ## Why the guard is asserted on the CALL, not on the effect
 *
 * A measured lesson from `odd/tasks/android-translation-history-isolation.md`:
 * row-count assertions are BLIND to a broken owner guard. Every DAO method here
 * filters by `user_id`, and the ownerless path resolves to the empty string, so
 * `WHERE user_id = ''` matches nothing when every row carries a real owner.
 * Deleting the `?: return` therefore changed no row and no count — the suite
 * stayed green. The only observable is the DAO call itself, which the
 * `historyQueries` / `ownersDeletedFrom` / `requestedLimits` recorders below pin.
 *
 * Both a MockK spy and a hand-written recorder are used deliberately: the
 * recorder proves the argument, the spy proves the invocation never happened.
 */
class RoomDefinitionHistoryRepositoryTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * A stand-in DAO that records every call it receives.
     *
     * The history query is kept separate from the other methods so a recorder
     * cannot be satisfied by an unrelated call.
     */
    private class FakeDao : DefinitionCacheDao {
        val rows = mutableMapOf<String, DefinitionCacheEntity>()

        /** Every `userId` passed to [history], in call order. Empty = never queried. */
        val historyQueries = mutableListOf<String>()

        /** Every `limit` passed to [history], in call order. */
        val requestedLimits = mutableListOf<Int>()

        /** Every `userId` passed to [deleteAll], in call order. Empty = never called. */
        val ownersDeletedFrom = mutableListOf<String>()

        override suspend fun get(
            userId: String,
            key: String,
        ): DefinitionCacheEntity? = rows["$userId::$key"]

        override suspend fun put(entry: DefinitionCacheEntity) {
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

        override fun history(
            userId: String,
            limit: Int,
        ): Flow<List<DefinitionCacheEntity>> {
            historyQueries += userId
            requestedLimits += limit
            return flowOf(
                rows.values
                    .filter { it.userId == userId }
                    .sortedByDescending { it.createdAtEpochMillis }
                    .take(limit),
            )
        }

        override suspend fun deleteAll(userId: String): Int {
            ownersDeletedFrom += userId
            val before = rows.size
            rows.entries.removeAll { (_, row) -> row.userId == userId }
            return before - rows.size
        }
    }

    private fun activeUser(userId: String? = "user-a"): ActiveUserProvider = ActiveUserProvider().apply { set(userId) }

    private fun payload(
        word: String,
        definitionText: String,
        readerLanguage: String = "es",
        wordLanguage: String = "en",
    ): String =
        DefinitionCacheCodec.encode(
            DefinitionResult(
                word = word,
                definitions = listOf(DefinitionItem(text = definitionText, partOfSpeech = "noun")),
                source = DefinitionSource.Native,
                wordLanguage = wordLanguage,
                readerLanguage = readerLanguage,
            ),
        )!!

    /**
     * Seeds [count] rows owned by [userId], oldest first by `createdAt`.
     *
     * The map key carries the account, or seeding B would overwrite A's rows and
     * the isolation assertions would pass for the wrong reason.
     */
    private fun seedRows(
        dao: FakeDao,
        userId: String,
        count: Int,
        wordPrefix: String = "palabra",
    ) {
        repeat(count) { index ->
            val normalized = "$userId-seed-$index"
            dao.rows["$userId::$normalized"] =
                DefinitionCacheEntity(
                    key = normalized,
                    userId = userId,
                    word = "$wordPrefix$index",
                    wordNormalized = normalized,
                    wordLanguage = "en",
                    readerLanguage = "es",
                    payload = payload("$wordPrefix$index", "meaning $index"),
                    createdAtEpochMillis = index.toLong(),
                )
        }
    }

    // ── H1: history() is newest first, for one account only ──────────

    @Test
    fun `history returns the active account's rows newest first`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            val entries = RoomDefinitionHistoryRepository(dao, activeUser("user-a")).history().first()

            assertEquals(3, entries.size)
            assertEquals(
                "a history that is not newest-first buries the word the reader just looked up",
                listOf("palabra2", "palabra1", "palabra0"),
                entries.map { it.word },
            )
            assertEquals(
                "the entry must expose something the reader can read as a meaning",
                listOf("meaning 2", "meaning 1", "meaning 0"),
                entries.map { it.definition },
            )
        }

    @Test
    fun `history never includes another account's rows`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            seedRows(dao, "user-b", 5, wordPrefix = "woord")
            val entries = RoomDefinitionHistoryRepository(dao, activeUser("user-a")).history().first()

            assertEquals(3, entries.size)
            assertTrue(
                "account B's words leaked into account A's history",
                entries.none { it.word.startsWith("woord") },
            )
            assertEquals(
                "each account's seed uses its own word prefix; A must see only its three",
                listOf("palabra2", "palabra1", "palabra0"),
                entries.map { it.word },
            )
        }

    @Test
    fun `history carries the language pair and the timestamp of the row`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 1)
            val entry = RoomDefinitionHistoryRepository(dao, activeUser("user-a")).history().first().single()

            assertEquals("en", entry.wordLanguage)
            assertEquals("es", entry.readerLanguage)
            assertEquals(0L, entry.createdAtEpochMillis)
        }

    // ── H2: clearHistory() deletes only the active account ───────────

    @Test
    fun `clearHistory deletes the active account's rows and leaves the other account untouched`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 4)
            seedRows(dao, "user-b", 6)
            RoomDefinitionHistoryRepository(dao, activeUser("user-a")).clearHistory()

            assertEquals(0, dao.count("user-a"))
            assertEquals(
                "account B's rows are not account A's to delete",
                6,
                dao.count("user-b"),
            )
            // Concretely, not only by count: a count that matches for the wrong
            // reason (rows replaced instead of kept) must still fail here.
            val survivor = dao.rows["user-b::user-b-seed-3"]
            assertEquals("user-b-seed-3", survivor?.key)
            assertEquals("user-b", survivor?.userId)
        }

    // ── H3: decision D5 — no active account reads and deletes nothing ─

    @Test
    fun `history is empty without an active account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            val entries = RoomDefinitionHistoryRepository(dao, activeUser(null)).history().first()

            assertEquals(
                "an ownerless read must not fall back to every account's rows",
                emptyList<String>(),
                entries.map { it.word },
            )
        }

    @Test
    fun `clearHistory deletes nothing without an active account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 7)
            RoomDefinitionHistoryRepository(dao, activeUser(null)).clearHistory()

            assertEquals(
                "the anonymous path owns no history, so it must not delete any",
                7,
                dao.count("user-a"),
            )
        }

    // ── H4: the owner guard is observable at the DAO boundary ────────

    @Test
    fun `history never reaches the dao without an active account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            RoomDefinitionHistoryRepository(dao, activeUser(null)).history().first()

            assertEquals(
                "the ownerless guard must return before the query, so the dao sees no call at all",
                emptyList<String>(),
                dao.historyQueries,
            )
            assertTrue(
                "no limit may be requested either: a recorded limit means the query happened",
                dao.requestedLimits.isEmpty(),
            )
        }

    @Test
    fun `clearHistory never reaches the dao without an active account`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 7)
            RoomDefinitionHistoryRepository(dao, activeUser(null)).clearHistory()

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
            RoomDefinitionHistoryRepository(dao, activeUser("user-a")).history().first()

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
    fun `clearHistory hands the active account id to the dao, never an empty owner`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 4)
            RoomDefinitionHistoryRepository(dao, activeUser("user-a")).clearHistory()

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

    // ── H5: a corrupt payload is skipped, never thrown ───────────────

    @Test
    fun `an entry whose payload fails to decode is omitted, not thrown`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 2)
            val corrupt = "user-a-seed-corrupt"
            dao.rows["user-a::$corrupt"] =
                DefinitionCacheEntity(
                    key = corrupt,
                    userId = "user-a",
                    word = "basura",
                    wordNormalized = corrupt,
                    wordLanguage = "en",
                    readerLanguage = "es",
                    payload = "{ not json",
                    // Newest, so a filter that forgot to run would surface it first.
                    createdAtEpochMillis = 99L,
                )

            val entries = RoomDefinitionHistoryRepository(dao, activeUser("user-a")).history().first()

            assertEquals(
                "a corrupt row is a MISS, not an error: the other entries must still be there",
                2,
                entries.size,
            )
            assertFalse(
                "the undecodable row must not be rendered with an empty meaning",
                entries.any { it.word == "basura" },
            )
            assertEquals(listOf("palabra1", "palabra0"), entries.map { it.word })
        }

    // ── H6: the limit is plumbed through to the query ────────────────

    @Test
    fun `history passes its limit to the dao`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            RoomDefinitionHistoryRepository(dao, activeUser("user-a"), limit = 2).history().first()

            assertEquals(
                "the limit must reach the query, or the screen loads the whole table",
                listOf(2),
                dao.requestedLimits,
            )
        }

    @Test
    fun `history honours the limit`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 10)
            val entries = RoomDefinitionHistoryRepository(dao, activeUser("user-a"), limit = 4).history().first()

            assertEquals(4, entries.size)
            assertEquals(
                "the limit must keep the NEWEST rows, not the first ones scanned",
                listOf("palabra9", "palabra8", "palabra7", "palabra6"),
                entries.map { it.word },
            )
        }

    @Test
    fun `a non-positive limit is clamped instead of reaching SQLite as LIMIT -1`() =
        runTest {
            val dao = FakeDao()
            seedRows(dao, "user-a", 3)
            RoomDefinitionHistoryRepository(dao, activeUser("user-a"), limit = 0).history().first()
            RoomDefinitionHistoryRepository(dao, activeUser("user-a"), limit = -5).history().first()

            assertTrue(
                "LIMIT 0 would only hide the bug; LIMIT -1 in SQLite means NO limit, so a " +
                    "zero/negative limit must never be forwarded as-is",
                dao.requestedLimits.none { it <= 0 },
            )
        }
}
