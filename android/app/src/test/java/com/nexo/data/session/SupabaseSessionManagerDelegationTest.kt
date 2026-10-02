package com.nexo.data.session

import com.nexo.domain.model.AuthSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.3.5 secrets encryption: [SupabaseSessionManager.setCurrentSession] is no longer a no-op — it delegates to the
 * encrypted [SessionStore] (wired to [PreferencesSessionStore] in production) instead of dropping the session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SupabaseSessionManagerDelegationTest {
    private class FakeSessionStore : SessionStore {
        var stored: AuthSession? = null

        override fun read(): AuthSession? = stored

        override fun write(session: AuthSession) {
            stored = session
        }

        override fun clear() {
            stored = null
        }
    }

    @Test
    fun setCurrentSession_persistsToEncryptedStore() =
        runTest {
            val store = FakeSessionStore()
            val manager = SupabaseSessionManager(store)
            val session = AuthSession(userId = "u1", email = "u@example.com")

            val result = manager.setCurrentSession(session)

            assertTrue(result.isSuccess)
            assertEquals(session, store.read())
        }

    @Test
    fun setCurrentSession_null_clearsEncryptedStore() =
        runTest {
            val store = FakeSessionStore()
            val manager = SupabaseSessionManager(store)
            manager.setCurrentSession(AuthSession(userId = "u1", email = "u@example.com"))

            val result = manager.setCurrentSession(null)

            assertTrue(result.isSuccess)
            assertNull(store.read())
        }
}
