package com.nexo.presentation.viewmodel.reader.interaction

import android.content.Context
import com.nexo.domain.definition.DefinitionErrorCode
import com.nexo.domain.definition.DefinitionFailure
import com.nexo.domain.definition.DefinitionItem
import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.DefinitionSource
import com.nexo.domain.definition.WordLanguage
import com.nexo.domain.repository.DefinitionRepository
import com.nexo.presentation.viewmodel.reader.DefinitionPanelState
import com.nexo.presentation.viewmodel.reader.ReaderInteractionState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefinitionManagerTest {
    private class ScriptedRepo(
        var result: (DefinitionRequest) -> DefinitionResult,
    ) : DefinitionRepository {
        val requests = mutableListOf<DefinitionRequest>()
        var calls = 0

        override suspend fun define(request: DefinitionRequest): DefinitionResult {
            calls++
            requests += request
            return result(request)
        }
    }

    private fun context(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns "message"
        return context
    }

    private fun store(selected: String): InteractionStateStore {
        val state = MutableStateFlow(ReaderInteractionState(selectedText = selected))
        return InteractionStateStore(state, MutableSharedFlow(extraBufferCapacity = 1))
    }

    private fun TestScope.manager(
        store: InteractionStateStore,
        repo: DefinitionRepository?,
    ) = DefinitionManager(
        store = store,
        definitionRepository = repo,
        appContext = context(),
        scope = this,
        mainDispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun result(
        word: String,
        readerLanguage: String,
        definitions: List<DefinitionItem> = listOf(DefinitionItem("casa", "sustantivo")),
        source: DefinitionSource? = DefinitionSource.Native,
        failure: DefinitionFailure? = null,
        wordLanguage: String = "en",
        wordLanguageKnown: Boolean = true,
    ) = DefinitionResult(
        word = word,
        definitions = definitions,
        source = source,
        wordLanguage = wordLanguage,
        readerLanguage = readerLanguage,
        wordLanguageKnown = wordLanguageKnown,
        failure = failure,
    )

    @Test
    fun `a definable selection reaches the repository`() =
        runTest {
            val store = store("house")
            val repo = ScriptedRepo { result(it.word, it.readerLanguage) }
            manager(store, repo).onDefine("es", WordLanguage.known("en"))

            assertEquals(1, repo.calls)
            assertTrue(store.value.showDefinitionPanel)
            assertTrue(store.value.definitionPanelState is DefinitionPanelState.Result)
            assertEquals("house", repo.requests.single().word)
            assertEquals("es", repo.requests.single().readerLanguage)
            assertEquals(
                "en",
                repo.requests
                    .single()
                    .wordLanguage.wordLanguage,
            )
        }

    @Test
    fun `a non-definable selection never reaches the repository`() =
        runTest {
            val store = store("She went to the market.")
            val repo = ScriptedRepo { result(it.word, it.readerLanguage) }
            manager(store, repo).onDefine("es", WordLanguage.known("en"))

            assertEquals(0, repo.calls)
            assertTrue(store.value.showDefinitionPanel)
            assertTrue(store.value.definitionPanelState is DefinitionPanelState.Empty)
        }

    @Test
    fun `an empty result and a failure are different panel states`() =
        runTest {
            val emptyStore = store("zzz")
            manager(emptyStore, ScriptedRepo { result(it.word, it.readerLanguage, definitions = emptyList(), source = null) })
                .onDefine("es", WordLanguage.known("en"))
            assertTrue(emptyStore.value.definitionPanelState is DefinitionPanelState.Empty)

            val failedStore = store("zzz")
            manager(
                failedStore,
                ScriptedRepo {
                    result(
                        it.word,
                        it.readerLanguage,
                        definitions = emptyList(),
                        source = null,
                        failure = DefinitionFailure(DefinitionErrorCode.NETWORK, "boom"),
                    )
                },
            ).onDefine("es", WordLanguage.known("en"))
            assertTrue(failedStore.value.definitionPanelState is DefinitionPanelState.Failure)
        }

    @Test
    fun `a language change re-runs the lookup for the same word without closing the panel`() =
        runTest {
            val store = store("house")
            val repo = ScriptedRepo { result(it.word, it.readerLanguage) }
            val manager = manager(store, repo)

            manager.onDefine("es", WordLanguage.known("en"))
            manager.onLanguageChanged("en", WordLanguage.known("en"))

            assertEquals(2, repo.calls)
            assertEquals(listOf("es", "en"), repo.requests.map { it.readerLanguage })
            assertEquals(listOf("house", "house"), repo.requests.map { it.word })
            assertTrue("the panel stayed open", store.value.showDefinitionPanel)
        }

    @Test
    fun `the panel-visible definition language follows the change made in the panel`() =
        runTest {
            val store = store("house")
            val repo = ScriptedRepo { result(it.word, it.readerLanguage) }
            val manager = manager(store, repo)

            manager.onDefine("es", WordLanguage.known("en"))
            assertEquals("es", store.value.definitionLanguage)

            // The reader picks another language in the panel. The chip's label
            // comes from this observable field, so if it does not move the chip
            // keeps showing the previous language — the shipped regression.
            manager.onLanguageChanged("en", WordLanguage.known("en"))

            assertEquals(
                "the panel must be able to see the language it just chose",
                "en",
                store.value.definitionLanguage,
            )
        }

    @Test
    fun `the panel-visible definition language is set even when nothing is looked up`() =
        runTest {
            val store = store("123")
            val repo = ScriptedRepo { result(it.word, it.readerLanguage) }

            manager(store, repo).onDefine("fr", WordLanguage.known("en"))

            assertEquals(0, repo.calls)
            assertEquals(
                "the selector must still show the real choice on a refused selection",
                "fr",
                store.value.definitionLanguage,
            )
        }

    @Test
    fun `an unknown word language is carried to the panel and stated`() =
        runTest {
            val store = store("house")
            val repo =
                ScriptedRepo { request ->
                    result(
                        request.word,
                        request.readerLanguage,
                        wordLanguage = request.wordLanguage.wordLanguage,
                        wordLanguageKnown = request.wordLanguage.known,
                    )
                }
            manager(store, repo).onDefine("es", WordLanguage.unknown())

            val state = store.value.definitionPanelState
            assertEquals("en", state.wordLanguage)
            assertEquals(false, state.wordLanguageKnown)
        }

    @Test
    fun `a missing repository reports a failure, not an empty result`() =
        runTest {
            val store = store("house")
            manager(store, null).onDefine("es", WordLanguage.known("en"))
            assertTrue(store.value.definitionPanelState is DefinitionPanelState.Failure)
        }

    @Test
    fun `opening the definition panel closes the translation panel`() =
        runTest {
            val state =
                MutableStateFlow(
                    ReaderInteractionState(
                        selectedText = "house",
                        showTranslationPanel = true,
                    ),
                )
            val store = InteractionStateStore(state, MutableSharedFlow(extraBufferCapacity = 1))
            manager(store, ScriptedRepo { result(it.word, it.readerLanguage) })
                .onDefine("es", WordLanguage.known("en"))
            assertEquals(false, store.value.showTranslationPanel)
        }

    @Test
    fun `dismissing the panel keeps the selection`() =
        runTest {
            val store = store("house")
            val manager = manager(store, ScriptedRepo { result(it.word, it.readerLanguage) })
            manager.onDefine("es", WordLanguage.known("en"))
            manager.onDismissDefinitionPanel()

            assertEquals(false, store.value.showDefinitionPanel)
            assertEquals("house", store.value.selectedText)
        }

    // ── Surrounding punctuation (Defect B1) ─────────────────────────────

    @Test
    fun `a quoted selection reaches the repository as the bare word`() =
        runTest {
            // The measured shape on `El asilo "de" ancianos`: the drag covers the
            // quote glyphs. The lookup must run for `de`, not be short-circuited.
            val store = store("\"de\"")
            val repo = ScriptedRepo { result(it.word, it.readerLanguage) }
            manager(store, repo).onDefine("es", WordLanguage.known("en"))

            assertEquals("the lookup must run for a quoted selection", 1, repo.calls)
            assertEquals("de", repo.requests.single().word)
            assertTrue(store.value.definitionPanelState is DefinitionPanelState.Result)
        }

    @Test
    fun `a trailing period is trimmed before the lookup`() =
        runTest {
            val store = store("\"efectismos.\"")
            val repo = ScriptedRepo { result(it.word, it.readerLanguage) }
            manager(store, repo).onDefine("es", WordLanguage.known("en"))

            assertEquals(1, repo.calls)
            assertEquals("efectismos", repo.requests.single().word)
        }

    @Test
    fun `the panel title shows the trimmed word, not the raw selection`() =
        runTest {
            val store = store("\"de\"")
            manager(store, ScriptedRepo { result(it.word, it.readerLanguage) })
                .onDefine("es", WordLanguage.known("en"))

            assertEquals("de", store.value.definitionWord)
            assertEquals("the selection itself is untouched for copy/share", "\"de\"", store.value.selectedText)
        }

    @Test
    fun `a selection of only punctuation is still refused and never reaches the repository`() =
        runTest {
            val store = store("\"\"")
            val repo = ScriptedRepo { result(it.word, it.readerLanguage) }
            manager(store, repo).onDefine("es", WordLanguage.known("en"))

            assertEquals(0, repo.calls)
            assertTrue(store.value.definitionPanelState is DefinitionPanelState.Empty)
        }
}
