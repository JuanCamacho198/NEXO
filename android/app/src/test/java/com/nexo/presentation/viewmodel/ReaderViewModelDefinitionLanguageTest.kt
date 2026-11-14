package com.nexo.presentation.viewmodel

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexo.data.session.DefinitionPreferences
import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.WordLanguage
import com.nexo.domain.repository.DefinitionRepository
import com.nexo.domain.usecase.UpdateReadingProgressUseCase
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The DEFINITION language SETTING must reach the LOOKUP (T9/T11 of
 * `android-define-selection`).
 *
 * This is the regression class the project hit once already: a preference that
 * was written and never READ. Storing a language is not enough — the lookup has
 * to consume it. These tests drive the real [DefinitionPreferences] store
 * through [ReaderViewModel.onDefineSelection] and assert on the
 * `readerLanguage` the repository actually receives.
 *
 * The word language is supplied separately (from the book) and travels on its
 * own field, so the two can never be conflated (invariant 1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderViewModelDefinitionLanguageTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class RecordingRepo : DefinitionRepository {
        val requests = mutableListOf<DefinitionRequest>()

        override suspend fun define(request: DefinitionRequest): DefinitionResult {
            requests += request
            return DefinitionResult(
                word = request.word,
                definitions = emptyList(),
                source = null,
                wordLanguage = request.wordLanguage.wordLanguage,
                wordLanguageKnown = request.wordLanguage.known,
                readerLanguage = request.readerLanguage,
            )
        }
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

    private fun store() = DefinitionPreferences(context, activeUserProviderFor(TEST_USER_ID))

    private fun viewModel(
        repo: DefinitionRepository,
        prefs: DefinitionPreferences,
    ): ReaderViewModel {
        val application = mockk<Application>(relaxed = true)
        return ReaderViewModel(
            application = application,
            readerRepository = FakeReaderRepository(),
            readingStatsRepository = FakeReadingStatsRepository(),
            updateReadingProgressUseCase = UpdateReadingProgressUseCase(FakeReaderRepository()),
            defaultBookId = null,
            activeUser = activeUserProviderFor(TEST_USER_ID),
            mainDispatcher = UnconfinedTestDispatcher(),
            definitionRepository = repo,
            definitionPreferences = prefs,
        )
    }

    /**
     * Sets `selectedText` directly on the holder's state. The public
     * `onTextSelection` builds a `rect=$rect` string that a pure JVM test cannot
     * render (`Rect.toString()` is not mockable) — the same workaround
     * `ReaderViewModelSelectionTest` documents.
     */
    @Suppress("UNCHECKED_CAST")
    private fun ReaderViewModel.selectForTest(text: String) {
        val field = interactionHolder::class.java.getDeclaredField("_state")
        field.isAccessible = true
        val state = field.get(interactionHolder) as kotlinx.coroutines.flow.MutableStateFlow<com.nexo.presentation.viewmodel.reader.ReaderInteractionState>
        state.value = state.value.copy(selectedText = text)
    }

    @Test
    fun `the stored definition language reaches the lookup`() =
        runTest {
            val repo = RecordingRepo()
            val prefs = store()
            prefs.save("fr")
            val vm = viewModel(repo, prefs)
            vm.selectForTest("juxtaposition")

            vm.onDefineSelection()

            assertEquals(
                "the setting must be the language the lookup asks for",
                "fr",
                repo.requests.single().readerLanguage,
            )
        }

    @Test
    fun `changing the setting changes the language the next lookup asks for`() =
        runTest {
            val repo = RecordingRepo()
            val prefs = store()
            prefs.save("fr")
            val vm = viewModel(repo, prefs)
            vm.selectForTest("juxtaposition")
            vm.onDefineSelection()

            prefs.save("de")
            vm.onDefineSelection()

            assertEquals(
                "a setting nobody reads is the defect; this reads it",
                listOf("fr", "de"),
                repo.requests.map { it.readerLanguage },
            )
        }

    @Test
    fun `an unchosen setting falls back to the app locale, never to the word language`() =
        runTest {
            val repo = RecordingRepo()
            val vm = viewModel(repo, store())
            vm.selectForTest("juxtaposition")

            vm.onDefineSelection()

            assertEquals(
                DefinitionPreferences.appLocaleLanguage(),
                repo.requests.single().readerLanguage,
            )
        }

    @Test
    fun `the word language travels separately from the definition language`() =
        runTest {
            val repo = RecordingRepo()
            val prefs = store()
            prefs.save("es")
            val vm = viewModel(repo, prefs)
            vm.selectForTest("juxtaposition")

            vm.onDefineSelection()

            val request = repo.requests.single()
            assertEquals("es", request.readerLanguage)
            assertEquals(
                "the book's language is UNKNOWN here (no book bound), never the reader's",
                WordLanguage.unknown(),
                request.wordLanguage,
            )
        }

    @Test
    fun `a non-candidate selection spends no lookup`() =
        runTest {
            val repo = RecordingRepo()
            val vm = viewModel(repo, store())
            vm.selectForTest("123")

            vm.onDefineSelection()

            assertEquals("123 has no letters; no request may be sent", 0, repo.requests.size)
        }
}
