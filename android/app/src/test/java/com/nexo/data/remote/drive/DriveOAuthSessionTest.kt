package com.nexo.data.remote.drive

import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [DriveOAuthSession]: the sealed outcome contract and the
 * persist-only-on-success rule (spec: "token pair persists; no error toast",
 * "mismatch fails, nothing persists", "cancelled flow → no toast, no token").
 */
class DriveOAuthSessionTest {
    private val store = mockk<DriveTokenStore>(relaxed = true)
    private val api = mockk<DriveTokenApi>()
    private val session =
        DriveOAuthSession(
            clientId = CLIENT_ID,
            redirectUri = "com.googleusercontent.apps.client-id:/oauth2redirect",
            tokenStore = store,
            tokenApi = api,
        )

    @Test
    fun matchingVerifier_persistsTokenPairAndReturnsSuccess() =
        runBlocking {
            val pair = DriveTokenPair(accessToken = "access-1", refreshToken = "refresh-1")
            coEvery {
                api.exchange(CLIENT_ID, "code-1", "com.googleusercontent.apps.client-id:/oauth2redirect", VERIFIER)
            } returns Result.success(pair)

            val result = session.complete("code-1", "state-1", "state-1", VERIFIER)

            assertTrue("expected Success, got $result", result is DriveAuthResult.Success)
            assertEquals("access-1", (result as DriveAuthResult.Success).accessToken)
            verify(exactly = 1) { store.persist(pair) }
        }

    @Test
    fun wrongVerifier_exchangeRejected_returnsFailureAndPersistsNothing() =
        runBlocking {
            coEvery { api.exchange(any(), any(), any(), any()) } returns
                Result.failure(
                    AppError(
                        category = ErrorCategory.AUTH,
                        code = "DRIVE_TOKEN_EXCHANGE_FAILED",
                        message = "Token exchange failed: invalid_grant",
                        component = "DriveTokenStore",
                    ),
                )

            val result = session.complete("code-1", "state-1", "state-1", "wrong-verifier")

            assertTrue("expected Failure, got $result", result is DriveAuthResult.Failure)
            assertTrue((result as DriveAuthResult.Failure).error.category == ErrorCategory.AUTH)
            verify(exactly = 0) { store.persist(any()) }
        }

    @Test
    fun canceled_noCode_returnsCanceledPersistsNothingAndNoError() =
        runBlocking {
            val result = session.complete(code = null, "state-1", "state-1", VERIFIER)

            assertEquals(DriveAuthResult.Canceled, result)
            verify(exactly = 0) { store.persist(any()) }
            // No Failure means no error surface: cancellation is silent by contract.
        }

    @Test
    fun invalidClientOrPermissionDenied_returnsFailure() =
        runBlocking {
            for (oauthError in listOf("invalid_client", "PERMISSION_DENIED")) {
                coEvery { api.exchange(any(), any(), any(), any()) } returns
                    Result.failure(
                        AppError(
                            category = ErrorCategory.AUTH,
                            code = "DRIVE_TOKEN_EXCHANGE_FAILED",
                            message = "Token exchange failed: $oauthError",
                            component = "DriveTokenStore",
                        ),
                    )

                val result = session.complete("code-1", "state-1", "state-1", VERIFIER)

                assertTrue("$oauthError should map to Failure, got $result", result is DriveAuthResult.Failure)
                val error = (result as DriveAuthResult.Failure).error
                assertTrue(error.category == ErrorCategory.AUTH)
                assertTrue(error.message.contains(oauthError))
                verify(exactly = 0) { store.persist(any()) }
            }
        }

    @Test
    fun stateMismatch_returnsFailureAndPersistsNothing() =
        runBlocking {
            val result = session.complete("code-1", "expected-state", "attacker-state", VERIFIER)

            assertTrue("expected Failure, got $result", result is DriveAuthResult.Failure)
            assertEquals("DRIVE_OAUTH_STATE_MISMATCH", (result as DriveAuthResult.Failure).error.code)
            verify(exactly = 0) { store.persist(any()) }
            coVerify(exactly = 0) { api.exchange(any(), any(), any(), any()) }
        }

    @Test
    fun networkFailure_mapsToFailureWithoutPersisting() =
        runBlocking {
            coEvery { api.exchange(any(), any(), any(), any()) } returns Result.failure(IOException("boom"))

            val result = session.complete("code-1", "state-1", "state-1", VERIFIER)

            assertTrue("expected Failure, got $result", result is DriveAuthResult.Failure)
            assertEquals(ErrorCategory.NETWORK, (result as DriveAuthResult.Failure).error.category)
            verify(exactly = 0) { store.persist(any()) }
        }

    @Test
    fun beginAuth_returnsVerifierChallengeAndState() {
        val auth = session.beginAuth()
        assertEquals(43, auth.verifier.length)
        assertEquals(Pkce.challenge(auth.verifier), auth.challenge)
        assertTrue(auth.state.isNotBlank())
        assertTrue("state must differ from verifier", auth.state != auth.verifier)
    }

    private companion object {
        const val CLIENT_ID = "client-id.apps.googleusercontent.com"
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    }
}
