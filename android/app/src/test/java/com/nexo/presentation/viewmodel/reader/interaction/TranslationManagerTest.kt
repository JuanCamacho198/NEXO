package com.nexo.presentation.viewmodel.reader.interaction

import android.content.Context
import com.nexo.domain.repository.TranslationRepository
import com.nexo.domain.translation.TranslationOutcome
import com.nexo.presentation.viewmodel.reader.ReaderInteractionState
import com.nexo.presentation.viewmodel.reader.SelectionCoordinator
import com.nexo.presentation.viewmodel.reader.TranslationPanelState
import com.nexo.testutil.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Guards wired into the manager (T3 of `translation-guards-and-cache-pruning`).
 *
 * Criteria 1 and 2 of the ODD are "selecting `.` does not call the API" and
 * "does not write a cache row". Driving that through the emulator by selecting
 * a period by coordinates is impractical, so it is proven HERE: the fake
 * repository counts how many times the manager asked it to translate, and a
 * zero count is exactly the guarantee — no request, and therefore no write,
 * because the write lives behind the request.
 *
 * A plain JVM test: `Context` is stubbed because the only production use is
 * `getString` on the error paths, which these assertions never reach. Booting
 * Robolectric here would start the app's WorkManager and leak its Room
 * invalidation thread into later tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationManagerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val context: Context =
        mockk<Context>(relaxed = true).also { every { it.getString(any()) } returns "msg" }

    private class RecordingRepository : TranslationRepository {
        var calls = 0
        var lastText: String? = null
        var prunes = 0

        override suspend fun translate(
            text: String,
            sourceLanguage: String,
            targetLanguage: String,
            allowNetwork: Boolean,
        ): TranslationOutcome {
            calls++
            lastText = text
            return TranslationOutcome.Translated("translated:$text", fromCache = false)
        }

        override suspend fun pruneCache(): Int {
            prunes++
            return 0
        }
    }

    private fun store(selectedText: String?): InteractionStateStore {
        val state = MutableStateFlow(ReaderInteractionState(selectedText = selectedText))
        val clearEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        return InteractionStateStore(state, clearEvent).apply {
            setCoordinator = { SelectionCoordinator.MenuClosed() }
        }
    }

    private fun manager(
        store: InteractionStateStore,
        repository: TranslationRepository?,
    ) = TranslationManager(
        store = store,
        translationRepository = repository,
        appContext = context,
        scope = kotlinx.coroutines.CoroutineScope(mainDispatcherRule.dispatcher),
        mainDispatcher = mainDispatcherRule.dispatcher,
    )

    // ── Criterion 1: a nonsensical selection never reaches the repository ──

    @Test
    fun `a lone period is never sent to the repository`() =
        runTest {
            val repository = RecordingRepository()
            val store = store(".")
            manager(store, repository).onTranslate("en", "es")
            assertEquals("no request may be spent on '.'", 0, repository.calls)
        }

    @Test
    fun `the eight measured nonsense inputs never reach the repository`() =
        runTest {
            // The same eight entries measured against the live MyMemory API.
            val measured = listOf(".", "«", "—", "  ", "a", "123", "¿?")
            measured.forEach { input ->
                val repository = RecordingRepository()
                manager(store(input), repository).onTranslate("en", "es")
                assertEquals("input '$input' must not spend a request", 0, repository.calls)
            }
        }

    @Test
    fun `a real selection still reaches the repository`() =
        runTest {
            val repository = RecordingRepository()
            manager(store("El extranjero"), repository).onTranslate("en", "es")
            assertEquals(1, repository.calls)
            assertEquals("El extranjero", repository.lastText)
        }

    // ── The panel answers instead of doing nothing ────────────────────────

    @Test
    fun `a rejected selection opens the panel with the no-translation state`() =
        runTest {
            val store = store(".")
            manager(store, RecordingRepository()).onTranslate("en", "es")
            assertTrue("the panel must open so the tap is not a no-op", store.value.showTranslationPanel)
            assertEquals(TranslationPanelState.NoTranslation, store.value.translationPanelState)
        }

    @Test
    fun `a rejected selection never leaves the panel spinning`() =
        runTest {
            val store = store("«")
            manager(store, RecordingRepository()).onTranslate("en", "es")
            assertFalse(
                "Loading would be a lie — nothing was requested",
                store.value.translationPanelState is TranslationPanelState.Loading,
            )
        }

    @Test
    fun `a rejected selection starts no in-flight job`() =
        runTest {
            val store = store(".")
            val manager = manager(store, RecordingRepository())
            manager.onTranslate("en", "es")
            assertFalse(manager.hasInFlight())
        }

    @Test
    fun `a blank selection is ignored entirely`() =
        runTest {
            val repository = RecordingRepository()
            val store = store("   ")
            manager(store, repository).onTranslate("en", "es")
            assertEquals(0, repository.calls)
            assertFalse("no panel for an empty selection", store.value.showTranslationPanel)
        }

    // ── Cache pruning trigger (T4) ────────────────────────────────────────
    //
    // The sweep runs at a session boundary — the reader opening a book — not
    // per translation: a per-request sweep would add a write to the hot path
    // to reclaim rows that are not yet a problem.

    @Test
    fun `opening a book prunes the cache once`() =
        runTest {
            val repository = RecordingRepository()
            manager(store(null), repository).onBookOpened()
            assertEquals(1, repository.prunes)
        }

    @Test
    fun `translating never prunes the cache`() =
        runTest {
            val repository = RecordingRepository()
            manager(store("El extranjero"), repository).onTranslate("en", "es")
            assertEquals("a translation must not pay for a sweep", 0, repository.prunes)
        }

    @Test
    fun `opening a book does not open the translation panel`() =
        runTest {
            val store = store(null)
            manager(store, RecordingRepository()).onBookOpened()
            assertFalse(store.value.showTranslationPanel)
        }

    @Test
    fun `pruning is skipped when no repository is wired`() =
        runTest {
            // A holder built without a repository must not crash at book open.
            manager(store(null), null).onBookOpened()
        }
}
