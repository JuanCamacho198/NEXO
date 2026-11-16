package com.nexo.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.sync.GoalValue
import com.nexo.data.sync.ReadingGoalRemoteDataSource
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.testutil.TEST_USER_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T2 (repository): local cache + remote coordination with the LWW rule and
 * offline tolerance. The remote is a scriptable fake so the offline path is
 * exercised deterministically instead of by unplugging a network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingGoalRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun providerFor(userId: String?) = ActiveUserProvider().apply { set(userId) }

    private fun localFor(
        userId: String,
        clock: () -> Long = { 0L },
    ) = ReadingGoalPreferences(context, providerFor(userId), clock)

    /** Scriptable remote: a value, an optional failure, and a write log. */
    private class FakeRemote(
        var value: GoalValue? = null,
        var failFetch: Boolean = false,
        var failSave: Boolean = false,
    ) : ReadingGoalRemoteDataSource {
        val saves = mutableListOf<Triple<String, Int, Long>>()

        override suspend fun fetchGoal(userId: String): GoalValue? {
            if (failFetch) throw java.io.IOException("offline")
            return value
        }

        override suspend fun saveGoal(
            userId: String,
            minutes: Int,
            updatedAt: Long,
        ) {
            if (failSave) throw java.io.IOException("offline")
            saves += Triple(userId, minutes, updatedAt)
            value = GoalValue(minutes, updatedAt)
        }
    }

    private fun repo(
        local: ReadingGoalPreferences,
        remote: ReadingGoalRemoteDataSource,
        now: () -> Long = { 0L },
    ) = ReadingGoalRepository(local, remote, activeUserIdProvider = { TEST_USER_ID }, now = now)

    @Test
    fun `fresh local with remote goal hydrates from remote`() =
        runTest {
            val remote = FakeRemote(value = GoalValue(30, 5_000L))
            val local = localFor(TEST_USER_ID)

            val result = repo(local, remote).sync(TEST_USER_ID)

            assertEquals(30, result.minutes)
            assertTrue(result.remoteReachable)
            assertEquals(30, local.loadValue(TEST_USER_ID)?.minutes)
            // Hydration must keep the REMOTE clock, not stamp it as fresh.
            assertEquals(5_000L, local.loadValue(TEST_USER_ID)?.updatedAt)
        }

    @Test
    fun `local-only goal is pushed to remote on first sync`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            local.save(45, 1_000L, TEST_USER_ID)
            val remote = FakeRemote(value = null)

            val result = repo(local, remote).sync(TEST_USER_ID)

            assertEquals(45, result.minutes)
            assertEquals(listOf(Triple(TEST_USER_ID, 45, 1_000L)), remote.saves)
        }

    @Test
    fun `newer local wins over older remote and is pushed`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            local.save(45, 9_000L, TEST_USER_ID)
            val remote = FakeRemote(value = GoalValue(10, 1_000L))

            val result = repo(local, remote).sync(TEST_USER_ID)

            assertEquals(45, result.minutes)
            assertEquals(45, remote.value?.minutes)
        }

    @Test
    fun `newer remote wins over older local and is hydrated`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            local.save(10, 1_000L, TEST_USER_ID)
            val remote = FakeRemote(value = GoalValue(30, 9_000L))

            val result = repo(local, remote).sync(TEST_USER_ID)

            assertEquals(30, result.minutes)
            assertEquals(30, local.loadValue(TEST_USER_ID)?.minutes)
            assertEquals(9_000L, local.loadValue(TEST_USER_ID)?.updatedAt)
        }

    @Test
    fun `offline sync keeps the local goal and is not treated as absence`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            local.save(30, 1_000L, TEST_USER_ID)
            val remote = FakeRemote(failFetch = true)

            val result = repo(local, remote).sync(TEST_USER_ID)

            // The bug being removed: an offline returning user must NOT be
            // reported as goal-less (which would show onboarding).
            assertEquals(30, result.minutes)
            // And the caller must be told the remote was NOT reached, so it does
            // not read a null as a definite "new account".
            assertTrue(!result.remoteReachable)
            assertEquals(30, local.loadValue(TEST_USER_ID)?.minutes)
        }

    @Test
    fun `offline sync with no local goal reports absence`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            val remote = FakeRemote(failFetch = true)

            val result = repo(local, remote).sync(TEST_USER_ID)

            assertNull(result.minutes)
            // Unresolved, not a confirmed absence: the gate must keep waiting
            // rather than send the user to onboarding.
            assertTrue(!result.remoteReachable)
        }

    @Test
    fun `absent on both sides reports absence`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            val remote = FakeRemote(value = null)

            val result = repo(local, remote).sync(TEST_USER_ID)

            assertNull(result.minutes)
            // The remote answered, so this IS a definite "new account".
            assertTrue(result.remoteReachable)
        }

    @Test
    fun `save writes local and remote with a shared clock`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            val remote = FakeRemote()

            val result = repo(local, remote, now = { 7_777L }).save(30, TEST_USER_ID)

            assertEquals(30, result.getOrNull())
            assertEquals(30, local.loadValue(TEST_USER_ID)?.minutes)
            assertEquals(7_777L, local.loadValue(TEST_USER_ID)?.updatedAt)
            assertEquals(listOf(Triple(TEST_USER_ID, 30, 7_777L)), remote.saves)
        }

    @Test
    fun `save survives a remote failure and keeps the local value`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            val remote = FakeRemote(failSave = true)

            val result = repo(local, remote, now = { 2_000L }).save(45, TEST_USER_ID)

            assertEquals(45, result.getOrNull())
            assertEquals(45, local.loadValue(TEST_USER_ID)?.minutes)
            assertTrue(remote.saves.isEmpty())
        }

    @Test
    fun `save with no owner writes nothing`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            val remote = FakeRemote()
            // A signed-out container: the provider yields null, so the ambient
            // resolution has no owner to write for (decision D5).
            val repository =
                ReadingGoalRepository(
                    local,
                    remote,
                    activeUserIdProvider = { null },
                    now = { 0L },
                )

            repository.save(30)

            assertNull(local.loadValue(TEST_USER_ID))
            assertTrue(remote.saves.isEmpty())
        }

    @Test
    fun `save then sync converges without a second remote write`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            val remote = FakeRemote()
            val repository = repo(local, remote, now = { 3_000L })

            repository.save(30, TEST_USER_ID)
            repository.sync(TEST_USER_ID)

            // The pushed value equals the remote, so reconcile must not push again.
            assertEquals(1, remote.saves.size)
        }
}
