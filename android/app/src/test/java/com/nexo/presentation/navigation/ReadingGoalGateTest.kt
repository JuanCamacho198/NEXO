package com.nexo.presentation.navigation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.repository.ReadingGoalRepository
import com.nexo.data.session.ReadingGoalPreferences
import com.nexo.data.sync.GoalValue
import com.nexo.data.sync.ReadingGoalRemoteDataSource
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.testutil.TEST_USER_ID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T3: the second-device onboarding flash.
 *
 * The gate must answer "does this account have a goal?" across devices and, when
 * it cannot, say so instead of guessing. `null` is what keeps the host on its
 * spinner; reading it as "no goal" is the flash being removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadingGoalGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun localFor(userId: String) = ReadingGoalPreferences(context, ActiveUserProvider().apply { set(userId) })

    private class FakeRemote(
        var value: GoalValue? = null,
        var failFetch: Boolean = false,
    ) : ReadingGoalRemoteDataSource {
        override suspend fun fetchGoal(userId: String): GoalValue? {
            if (failFetch) throw java.io.IOException("offline")
            return value
        }

        override suspend fun saveGoal(
            userId: String,
            minutes: Int,
            updatedAt: Long,
        ) {
            value = GoalValue(minutes, updatedAt)
        }
    }

    private fun repository(
        remote: ReadingGoalRemoteDataSource,
        userId: String = TEST_USER_ID,
    ) = ReadingGoalRepository(
        local = localFor(userId),
        remote = remote,
        activeUserIdProvider = { userId },
    )

    @Test
    fun `second device with a remote goal resolves to true`() =
        runTest {
            // Device B: empty local cache, goal already on the server.
            val resolved =
                ReadingGoalGate.resolve(
                    repository(FakeRemote(value = GoalValue(30, 5_000L))),
                    TEST_USER_ID,
                )

            assertEquals(true, resolved)
        }

    @Test
    fun `confirmed remote absence resolves to false`() =
        runTest {
            val resolved =
                ReadingGoalGate.resolve(repository(FakeRemote(value = null)), TEST_USER_ID)

            // Only a reached remote with no row is a genuinely new account.
            assertEquals(false, resolved)
        }

    @Test
    fun `offline with no cache stays unresolved instead of false`() =
        runTest {
            val resolved =
                ReadingGoalGate.resolve(repository(FakeRemote(failFetch = true)), TEST_USER_ID)

            // This is the returning user on a plane: unknown, not "new".
            assertNull(resolved)
        }

    @Test
    fun `offline with a cached goal resolves to true`() =
        runTest {
            val local = localFor(TEST_USER_ID)
            local.save(30, 1_000L, TEST_USER_ID)
            val repository =
                ReadingGoalRepository(
                    local = local,
                    remote = FakeRemote(failFetch = true),
                    activeUserIdProvider = { TEST_USER_ID },
                )

            assertEquals(true, ReadingGoalGate.resolve(repository, TEST_USER_ID))
        }

    @Test
    fun `no account id stays unresolved`() =
        runTest {
            val resolved =
                ReadingGoalGate.resolve(repository(FakeRemote(value = GoalValue(30, 1L))), userId = null)

            assertNull(resolved)
        }
}
