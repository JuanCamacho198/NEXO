package com.nexo.data.remote.drive

import android.content.Context
import com.nexo.domain.error.AppError
import com.nexo.domain.error.ErrorCategory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [DriveCoordinator] (D2: refresh-token grant re-issues access). */
class DriveCoordinatorTest {
    private val store = mockk<DriveTokenStore>(relaxed = true)
    private val api = mockk<DriveTokenApi>()
    private val coordinator = DriveCoordinator(mockk<Context>(relaxed = true), store, api, "client")

    @Test fun refresh_reissuesAccessTokenAndPersists() =
        runBlocking {
            every { store.refreshToken() } returns "r1"
            coEvery { api.refresh("client", "r1") } returns Result.success(DriveTokenPair("a2", "r2"))
            assertEquals("a2", coordinator.refreshAccessToken().getOrThrow())
            verify { store.persist(match { it.accessToken == "a2" && it.refreshToken == "r2" }) }
        }

    @Test fun refresh_withoutRefreshToken_clearsAndFails() =
        runBlocking {
            every { store.refreshToken() } returns null
            val r = coordinator.refreshAccessToken()
            assertTrue(r.isFailure)
            assertEquals("DRIVE_NO_REFRESH_TOKEN", (r.exceptionOrNull() as AppError).code)
            verify { store.clear() }
        }

    @Test fun refresh_rejected_clearsAndSurfacesAuthError() =
        runBlocking {
            every { store.refreshToken() } returns "r1"
            coEvery { api.refresh(any(), any()) } returns
                Result.failure(
                    AppError(ErrorCategory.AUTH, "REJECTED", "invalid_grant", "test"),
                )
            val r = coordinator.refreshAccessToken()
            assertTrue(r.isFailure)
            assertEquals(ErrorCategory.AUTH, (r.exceptionOrNull() as AppError).category)
            verify(exactly = 0) { store.clear() }
        }
}
