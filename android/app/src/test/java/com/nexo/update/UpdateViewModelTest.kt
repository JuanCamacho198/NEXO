package com.nexo.update

import com.nexo.data.update.UpdateDownloader
import com.nexo.domain.update.CheckForUpdatesUseCase
import com.nexo.domain.update.UpdateErrorKind
import com.nexo.domain.update.UpdateFeedFailure
import com.nexo.domain.update.UpdateFeedFetch
import com.nexo.presentation.viewmodel.UpdateUiState
import com.nexo.presentation.viewmodel.UpdateViewModel
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val INSTALLED = 300

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val downloader: UpdateDownloader = mockk(relaxed = true)

    @After
    fun tearDown() = unmockkAll()

    private fun viewModel(
        fetch: UpdateFeedFetch,
        gate: FakeGate = FakeGate(),
        suppression: FakeSuppression = FakeSuppression(),
        dispatcher: CoroutineDispatcher,
        configured: Boolean = true,
    ): UpdateViewModel {
        val useCase =
            CheckForUpdatesUseCase(FakeFetcher(fetch, configured), suppression, gate) { 1_000L }
        return UpdateViewModel(
            useCase = useCase,
            suppression = suppression,
            networkGate = gate,
            downloader = downloader,
            installedVersionCode = INSTALLED,
            mainDispatcher = dispatcher,
            nowEpochMs = { 1_000L },
        )
    }

    @Test
    fun manualCheck_newerFeed_showsAvailableDialogFlow() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val vm = viewModel(foundFetch(), dispatcher = dispatcher)

            vm.checkManually()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state is UpdateUiState.Available)
            assertEquals("0.4.0", (state as UpdateUiState.Available).candidate.version)
        }

    @Test
    fun manualCheck_currentBuild_showsUpToDate() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val current =
                foundFetch().copy(version = "0.3.0", versionCode = INSTALLED)
            val vm = viewModel(current, dispatcher = dispatcher)

            vm.checkManually()
            advanceUntilIdle()

            assertEquals(UpdateUiState.UpToDate, vm.uiState.value)
        }

    @Test
    fun manualCheck_offline_showsErrorNeverUpToDate() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val vm =
                viewModel(
                    UpdateFeedFetch.Failed(UpdateFeedFailure.UNREACHABLE),
                    gate = FakeGate(online = false),
                    dispatcher = dispatcher,
                )

            vm.checkManually()
            advanceUntilIdle()

            assertEquals(UpdateUiState.Error(UpdateErrorKind.OFFLINE), vm.uiState.value)
        }

    @Test
    fun startupCheck_runsOncePerLaunch() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val vm = viewModel(foundFetch(), dispatcher = dispatcher)

            vm.checkAtStartup()
            advanceUntilIdle()
            assertTrue(vm.uiState.value is UpdateUiState.Available)

            vm.dismiss()
            vm.checkAtStartup()
            advanceUntilIdle()

            assertEquals(UpdateUiState.Idle, vm.uiState.value)
        }

    @Test
    fun remindLater_suppressesVersionAndDismisses() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val suppression = FakeSuppression()
            val vm = viewModel(foundFetch(), suppression = suppression, dispatcher = dispatcher)

            vm.checkManually()
            advanceUntilIdle()
            vm.remindLater()

            assertEquals(UpdateUiState.Idle, vm.uiState.value)
            assertTrue(suppression.dismissed.containsKey("0.4.0"))
        }

    @Test
    fun updateNow_onMetered_requestsConsentFirst() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val vm = viewModel(foundFetch(), gate = FakeGate(metered = true), dispatcher = dispatcher)

            vm.checkManually()
            advanceUntilIdle()
            vm.updateNow()

            assertTrue(vm.meteredConsentPending.value)
        }

    @Test
    fun updateNow_unmetered_enqueuesDownload() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            every { downloader.fileNameFor(any(), any()) } returns "nexo-update-v0.4.0.apk"
            every { downloader.enqueue(any(), any()) } returns 42L
            val vm = viewModel(foundFetch(), dispatcher = dispatcher)

            vm.checkManually()
            advanceUntilIdle()
            vm.updateNow()

            assertFalse(vm.meteredConsentPending.value)
            verify { downloader.enqueue("0.4.0", "https://example.com/nexo-android-v0.4.0.apk") }
        }

    @Test
    fun disabledFeed_manualCheck_staysIdle() =
        runTest(StandardTestDispatcher()) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val vm =
                viewModel(
                    UpdateFeedFetch.Failed(UpdateFeedFailure.DISABLED),
                    dispatcher = dispatcher,
                    configured = false,
                )

            assertFalse(vm.isFeedEnabled())
            vm.checkManually()
            advanceUntilIdle()

            assertEquals(UpdateUiState.Idle, vm.uiState.value)
        }
}
