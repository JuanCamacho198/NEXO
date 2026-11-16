package com.nexo.data.remote.drive

import com.nexo.domain.session.ActiveUserProvider
import com.nexo.testutil.FakeSharedPreferences
import com.nexo.testutil.OTHER_USER_ID
import com.nexo.testutil.TEST_USER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10: Google Drive OAuth tokens are per-account.
 *
 * This is the security half of the task: before, account B inherited account A's
 * Drive access because both read one global `nexo_drive_token_store`.
 *
 * Uses [FakeSharedPreferences] in place of the Keystore-backed file (see
 * [PerAccountDriveTokenStore]); the encryption itself is Android's and unchanged,
 * so what is asserted here is the account scoping the task asked for.
 */
class DriveTokenIsolationTest {
    /** One in-memory file per owner id, mirroring the per-account file names. */
    private val filesByOwner = mutableMapOf<String, FakeSharedPreferences>()

    private fun storeFor(provider: ActiveUserProvider): PerAccountDriveTokenStore =
        PerAccountDriveTokenStore(provider) { owner ->
            filesByOwner.getOrPut(owner) { FakeSharedPreferences() }
        }

    private fun providerFor(userId: String?) = ActiveUserProvider().apply { set(userId) }

    @Test
    fun `tokens are independent per account`() {
        val accountA = storeFor(providerFor(TEST_USER_ID))
        accountA.persist(DriveTokenPair(accessToken = "a-access", refreshToken = "a-refresh"))

        val accountB = storeFor(providerFor(OTHER_USER_ID))
        // B has never authorized Drive: it must not see A's tokens.
        assertNull(accountB.accessToken())
        assertNull(accountB.refreshToken())
        assertFalse(accountB.isAuthorized())

        accountB.persist(DriveTokenPair(accessToken = "b-access", refreshToken = "b-refresh"))

        // A is untouched, and the two files are different.
        assertEquals("a-access", accountA.accessToken())
        assertEquals("b-access", accountB.accessToken())
        assertTrue(filesByOwner.keys.containsAll(listOf(TEST_USER_ID, OTHER_USER_ID)))
    }

    @Test
    fun `sign-out then a second account cannot read the first account's tokens`() {
        val provider = providerFor(TEST_USER_ID)
        val store = storeFor(provider)
        store.persist(DriveTokenPair(accessToken = "a-access", refreshToken = "a-refresh"))

        // Sign out: no owner, no file, so nothing is readable.
        provider.clear()
        assertNull(store.accessToken())
        assertFalse(store.isAuthorized())

        // Sign in as B on the same store instance.
        provider.set(OTHER_USER_ID)
        assertNull(store.accessToken())
    }

    @Test
    fun `anonymous path stores nothing and reads as unauthorized`() {
        val provider = providerFor("local-abc")
        val store = storeFor(provider)

        store.persist(DriveTokenPair(accessToken = "anon-access", refreshToken = null))

        // The throwaway id is dropped, so no file was ever opened for it.
        assertNull(store.accessToken())
        assertFalse(store.isAuthorized())
        assertTrue(filesByOwner.isEmpty())
    }

    @Test
    fun `clear removes only the active account's tokens`() {
        val accountA = storeFor(providerFor(TEST_USER_ID))
        val accountB = storeFor(providerFor(OTHER_USER_ID))
        accountA.persist(DriveTokenPair(accessToken = "a-access", refreshToken = "a-refresh"))
        accountB.persist(DriveTokenPair(accessToken = "b-access", refreshToken = "b-refresh"))

        accountA.clear()

        assertNull(accountA.accessToken())
        assertEquals("b-access", accountB.accessToken())
    }
}
