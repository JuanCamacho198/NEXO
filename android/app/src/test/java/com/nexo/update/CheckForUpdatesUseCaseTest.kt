package com.nexo.update

import com.nexo.domain.update.CheckForUpdatesUseCase
import com.nexo.domain.update.UpdateCheckOutcome
import com.nexo.domain.update.UpdateErrorKind
import com.nexo.domain.update.UpdateFeedFailure
import com.nexo.domain.update.UpdateFeedFetch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val INSTALLED = 300

class CheckForUpdatesUseCaseTest {
    private fun useCase(
        fetch: UpdateFeedFetch,
        gate: FakeGate = FakeGate(),
        suppression: FakeSuppression = FakeSuppression(),
    ): CheckForUpdatesUseCase = CheckForUpdatesUseCase(FakeFetcher(fetch), suppression, gate) { suppression.now }

    @Test
    fun startup_newerFeed_offersUpdate(): Unit =
        runBlocking {
            val outcome = useCase(foundFetch()).check(manual = false, installedVersionCode = INSTALLED)

            assertTrue(outcome is UpdateCheckOutcome.Available)
        }

    @Test
    fun startup_equalVersion_offersNothing(): Unit =
        runBlocking {
            val outcome = useCase(foundFetch(versionCode = INSTALLED)).check(manual = false, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.UpToDate, outcome)
        }

    @Test
    fun startup_suppressedVersion_staysSilent(): Unit =
        runBlocking {
            val suppression = FakeSuppression().apply { remindLater("0.4.0", 1_000L) }
            val outcome = useCase(foundFetch(), suppression = suppression).check(manual = false, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Deferred, outcome)
        }

    @Test
    fun manual_bypassesSuppression(): Unit =
        runBlocking {
            val suppression = FakeSuppression().apply { remindLater("0.4.0", 1_000L) }
            val outcome = useCase(foundFetch(), suppression = suppression).check(manual = true, installedVersionCode = INSTALLED)

            assertTrue(outcome is UpdateCheckOutcome.Available)
        }

    @Test
    fun startup_newerFeedVersion_ignoresOldSuppression(): Unit =
        runBlocking {
            val suppression = FakeSuppression().apply { remindLater("0.4.0", 1_000L) }
            val outcome =
                useCase(foundFetch(version = "0.5.0", versionCode = 500), suppression = suppression)
                    .check(manual = false, installedVersionCode = INSTALLED)

            assertTrue(outcome is UpdateCheckOutcome.Available)
        }

    @Test
    fun unknownChannel_offersNothing(): Unit =
        runBlocking {
            val outcome = useCase(foundFetch(channel = "beta")).check(manual = true, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.UpToDate, outcome)
        }

    @Test
    fun startup_offline_defersSilently(): Unit =
        runBlocking {
            val outcome =
                useCase(foundFetch(), gate = FakeGate(online = false))
                    .check(manual = false, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Deferred, outcome)
        }

    @Test
    fun startup_metered_defersSilently(): Unit =
        runBlocking {
            val outcome =
                useCase(foundFetch(), gate = FakeGate(metered = true))
                    .check(manual = false, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Deferred, outcome)
        }

    @Test
    fun manual_offline_reportsOfflineError(): Unit =
        runBlocking {
            val outcome =
                useCase(
                    UpdateFeedFetch.Failed(UpdateFeedFailure.UNREACHABLE),
                    gate = FakeGate(online = false),
                ).check(manual = true, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Error(UpdateErrorKind.OFFLINE), outcome)
        }

    @Test
    fun manual_unreachable_reportsUnreachable(): Unit =
        runBlocking {
            val outcome =
                useCase(UpdateFeedFetch.Failed(UpdateFeedFailure.UNREACHABLE))
                    .check(manual = true, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Error(UpdateErrorKind.UNREACHABLE), outcome)
        }

    @Test
    fun manual_malformed_reportsMalformedNeverUpToDate(): Unit =
        runBlocking {
            val outcome =
                useCase(UpdateFeedFetch.Failed(UpdateFeedFailure.MALFORMED))
                    .check(manual = true, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Error(UpdateErrorKind.MALFORMED), outcome)
        }

    @Test
    fun disabledFeed_reportsDisabled(): Unit =
        runBlocking {
            val outcome =
                useCase(UpdateFeedFetch.Failed(UpdateFeedFailure.DISABLED))
                    .check(manual = true, installedVersionCode = INSTALLED)

            assertEquals(UpdateCheckOutcome.Disabled, outcome)
        }
}
