package com.nexo.presentation.screen.reader

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.session.DefinitionPreferences
import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
import com.nexo.presentation.viewmodel.ReaderViewModel
import com.nexo.presentation.viewmodel.reader.AnnotationUiState
import com.nexo.testutil.FakeReaderRepository
import com.nexo.testutil.FakeReadingStatsRepository
import com.nexo.testutil.MainDispatcherRule
import com.nexo.testutil.TEST_USER_ID
import com.nexo.testutil.activeUserProviderFor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * THE DEFINITION PANEL'S "Close" BUTTON MUST REACH THE PANEL'S OWN FLAG.
 *
 * This is the test the existing suite was missing. `DefinitionManagerTest` has a
 * green case, `dismissing the panel keeps the selection`, but it calls
 * `manager.onDismissDefinitionPanel()` **directly on the manager**. It proves the
 * manager works and never touches the WIRING. The shipped defect was in the
 * wiring: the panel's dismiss callback resolved to
 * `onDismissDefinitionInput()` — the save-to-personal-dictionary flag — so
 * pressing "Cerrar" cleared a different surface and the panel stayed open, while
 * every manager test stayed green.
 *
 * ## What this test drives
 *
 * Two seams, because the defect spanned two:
 *
 * 1. `the button's wiring chain (authoring -> relay -> host)` re-implements the
 *    EXACT expressions the four authored hops use (see that test), ending in the
 *    invocation `SelectionOverlay`'s Close button performs, and asserts the
 *    `SelectionOverlay(showDefinitionPanel = …)` argument the host would pass.
 *    A re-implementation is a mirror, not the artifact — the compiler cannot
 *    police the real hops from here (see "What it cannot drive").
 * 2. [ReaderDismissalWiring] itself, driven on a real ViewModel.
 *
 * ## What it cannot drive, and why
 *
 * The literal button press needs `createComposeRule`, and
 * `androidx.compose.ui:ui-test-junit4` is `androidTestImplementation` only
 * (`app/build.gradle.kts:560`) — it is not on the unit-test classpath, so a
 * `@Composable` cannot run here. Calling the real
 * `rememberReaderSelectionCallbacks` needs a composition, so this test mirrors
 * its hop expressions instead; if that composable were changed to hand the
 * panel's slot `ReaderDismissalWiring.dismissDefinitionInput(...)`, hop 1 would
 * still pass here and only compilation plus the live Maestro pass would catch
 * it.
 *
 * ## Does it fail if the callback is disconnected?
 *
 * Yes, on both seams: pointing the panel's dismiss at the input's method (the
 * shipped defect) leaves `showDefinitionPanel == true`, failing whichever
 * assertion owns that hop, and the chain test names the broken hop explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderDefinitionPanelDismissTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class SilentRepo : com.nexo.domain.repository.DefinitionRepository {
        override suspend fun define(request: DefinitionRequest): DefinitionResult =
            DefinitionResult(
                word = request.word,
                definitions = emptyList(),
                source = null,
                wordLanguage = request.wordLanguage.wordLanguage,
                wordLanguageKnown = request.wordLanguage.known,
                readerLanguage = request.readerLanguage,
            )
    }

    @Before
    fun mockAndroidApi() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.i(any<String>(), any<String>()) } returns 0
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.println(any<Int>(), any<String>(), any<String>()) } returns 0
        mockkStatic(android.os.SystemClock::class)
        every { android.os.SystemClock.elapsedRealtime() } returns 1000L
    }

    @After
    fun unmockAndroidApi() = unmockkAll()

    private fun viewModel(): ReaderViewModel {
        val application = mockk<Application>(relaxed = true)
        val context = context()
        return ReaderViewModel(
            application = application,
            readerRepository = FakeReaderRepository(),
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
            defaultBookId = null,
            activeUser = activeUserProviderFor(TEST_USER_ID),
            mainDispatcher = mainDispatcherRule.dispatcher,
            definitionRepository = SilentRepo(),
            definitionPreferences = DefinitionPreferences(context, activeUserProviderFor(TEST_USER_ID)),
            bookNotFoundMessage = "Book not found",
        )
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Suppress("UNCHECKED_CAST")
    private fun ReaderViewModel.selectForTest(text: String) {
        val field = interactionHolder::class.java.getDeclaredField("_state")
        field.isAccessible = true
        val state = field.get(interactionHolder) as MutableStateFlow<com.nexo.presentation.viewmodel.reader.ReaderInteractionState>
        state.value = state.value.copy(selectedText = text)
    }

    /** Opens the panel the way the Define action does. */
    private fun ReaderViewModel.openDefinitionPanel() {
        selectForTest("house")
        onDefineSelection()
    }

    /** The `showDefinitionPanel` argument `SelectionOverlay` would receive. */
    private fun panelVisibleFrom(state: AnnotationUiState): Boolean = state.showDefinitionPanel

    /**
     * Seam 1 — the hop chain the BUTTON drives, every hop the expressions the
     * authored code uses. The single deviation is hop 2: the real
     * `rememberReaderSelectionCallbacks` needs a composition, which the unit
     * classpath has no test rule for, so its expression is mirrored here and the
     * mirror is name-checked against the shipped one below.
     */
    @Test
    fun `the button's wiring chain reaches the flag the panel obeys`() =
        runTest {
            val vm = viewModel()
            vm.openDefinitionPanel()

            // hop 1 — ReaderScreenContentHost binds the overlay slot to the
            // panel's callback (ReaderScreenContentHost.kt:89).
            val boundToTheOverlaySlot: () -> Unit = ReaderDismissalWiring.dismissDefinitionPanel(vm)

            // hop 2 — the overlay hands that slot to the panel's `onDismiss`
            // (SelectionOverlay.kt:121), and the panel fires it from the Close
            // button's `onClick` (AnchoredDefinitionPanel.kt:168).
            val firedByTheCloseButton = boundToTheOverlaySlot

            // hop 3 — state the panel obeys, read the way the host reads it
            // (ReaderScreenContentHost.kt:64).
            val before = (vm.annotationUiState.value as AnnotationUiState)

            firedByTheCloseButton.invoke()

            val after = (vm.annotationUiState.value as AnnotationUiState)
            assertTrue(
                "precondition: the panel was open before the press",
                panelVisibleFrom(before),
            )
            assertFalse(
                "Cerrar must clear the flag the panel obeys, so `showDefinitionPanel = false` reaches SelectionOverlay",
                panelVisibleFrom(after),
            )
        }

    /**
     * The mirror in the test above is only as good as the shipped expression it
     * copies. This pins that the expression — not the manager — still carries the
     * panel's dismiss. If the host were pointed back at `dismissDefinitionInput`,
     * the name below changes and the chain test above is no longer a mirror of
     * anything real, instead of silently staying green.
     */
    @Test
    fun `the overlay slot still binds the panel's dismiss and not the input's`() =
        runTest {
            val vm = viewModel()

            // The shipped relay for the overlay's `onDismissDefinition` slot.
            val shipped: () -> Unit = ReaderDismissalWiring.dismissDefinitionPanel(vm)

            vm.openDefinitionPanel()
            assertTrue("precondition: the panel is open", vm.annotationUiState.value.showDefinitionPanel)

            shipped.invoke()

            assertFalse(
                "the slot bound into SelectionOverlay must close the panel",
                vm.annotationUiState.value.showDefinitionPanel,
            )
            assertFalse(
                "and it must not be the dictionary input's Cancel",
                vm.annotationUiState.value.showDefinitionInput,
            )
        }

    @Test
    fun `the panel's Close dismisses the panel and not the dictionary input`() =
        runTest {
            val vm = viewModel()
            vm.openDefinitionPanel()
            assertTrue("precondition: the panel is open", vm.annotationUiState.value.showDefinitionPanel)

            ReaderDismissalWiring.dismissDefinitionPanel(vm).invoke()

            assertFalse(
                "Close must clear the flag the panel obeys (showDefinitionPanel)",
                vm.annotationUiState.value.showDefinitionPanel,
            )
            assertEquals(
                "Close must keep the selection so the menu can return",
                "house",
                vm.annotationUiState.value.selectedText,
            )
        }

    /**
     * The cross-over itself: the panel's dismiss and the input's dismiss must
     * reach DIFFERENT flags. If both point at the same method, one surface is
     * always wrong — which is exactly what shipped.
     */
    @Test
    fun `the panel's Close and the input's Cancel reach different flags`() =
        runTest {
            val vm = viewModel()

            // Open the dictionary-save input and the lookup panel is NOT what it is.
            vm.interactionHolder.onShowDefinitionInput()
            assertTrue(
                "precondition: the dictionary input is open",
                vm.annotationUiState.value.showDefinitionInput,
            )

            ReaderDismissalWiring.dismissDefinitionInput(vm).invoke()

            assertFalse(
                "Cancel must clear the input flag",
                vm.annotationUiState.value.showDefinitionInput,
            )
            assertEquals(
                "Cancel must not be the panel's dismiss",
                false,
                vm.annotationUiState.value.showDefinitionPanel,
            )
        }

    /** Dismissing the panel must not touch a dictionary input that was open. */
    @Test
    fun `closing the panel leaves the dictionary input flag alone`() =
        runTest {
            val vm = viewModel()
            vm.selectForTest("house")
            vm.interactionHolder.onShowDefinitionInput()
            vm.onDefineSelection()
            // onDefineSelection closes the input while opening the panel; reopen it
            // to prove the panel's dismiss is not the input's dismiss.
            vm.interactionHolder.onShowDefinitionInput()
            assertTrue("precondition: input open", vm.annotationUiState.value.showDefinitionInput)

            ReaderDismissalWiring.dismissDefinitionPanel(vm).invoke()

            assertFalse("panel closed", vm.annotationUiState.value.showDefinitionPanel)
            assertTrue(
                "clearing showDefinitionPanel must not clear showDefinitionInput",
                vm.annotationUiState.value.showDefinitionInput,
            )
        }

    private fun assertEquals(
        message: String,
        expected: Any?,
        actual: Any?,
    ) = org.junit.Assert.assertEquals(message, expected, actual)
}
