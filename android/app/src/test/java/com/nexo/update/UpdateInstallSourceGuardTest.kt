package com.nexo.update

import com.nexo.domain.update.CheckForUpdatesUseCase
import com.nexo.domain.update.UpdateCheckOutcome
import com.nexo.domain.update.UpdateErrorKind
import com.nexo.domain.update.UpdateInstallSource
import com.nexo.domain.update.UpdateInstallSourceReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val INSTALLED = 300

/**
 * B3: the updater must refuse to self-install over a build a store installed.
 * One case per installer source: store, sideload, unknown.
 */
class UpdateInstallSourceGuardTest {
    private fun useCase(source: UpdateInstallSource): CheckForUpdatesUseCase =
        CheckForUpdatesUseCase(
            FakeFetcher(foundFetch()),
            FakeSuppression(),
            FakeGate(),
            reader(source),
        ) { 1_000L }

    private fun reader(source: UpdateInstallSource): UpdateInstallSourceReader =
        object : UpdateInstallSourceReader {
            override fun current(): UpdateInstallSource = source
        }

    @Test
    fun storeBuild_refusesWithManagedInstallError(): Unit =
        runBlocking {
            val outcome = useCase(UpdateInstallSource.STORE).check(manual = true, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Error(UpdateErrorKind.MANAGED_INSTALL), outcome)
        }

    @Test
    fun sideloadedBuild_offersTheUpdate(): Unit =
        runBlocking {
            val outcome = useCase(UpdateInstallSource.SIDELOAD).check(manual = true, installedVersionCode = INSTALLED)

            assertTrue(outcome is UpdateCheckOutcome.Available)
        }

    @Test
    fun unknownInstallerSource_refusesToo(): Unit =
        runBlocking {
            val outcome = useCase(UpdateInstallSource.UNKNOWN).check(manual = true, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Error(UpdateErrorKind.MANAGED_INSTALL), outcome)
        }

    @Test
    fun storeBuild_startupCheckNeverOffersAnInstall(): Unit =
        runBlocking {
            val outcome = useCase(UpdateInstallSource.STORE).check(manual = false, installedVersionCode = INSTALLED)

            // Not Available: never offer an install this build cannot perform.
            assertEquals(UpdateCheckOutcome.Error(UpdateErrorKind.MANAGED_INSTALL), outcome)
        }
}
