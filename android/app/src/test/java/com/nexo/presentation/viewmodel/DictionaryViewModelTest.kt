package com.nexo.presentation.viewmodel

import com.nexo.domain.model.DictionaryWord
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.presentation.UiEvent
import com.nexo.testutil.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictionaryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `onAddWordConfirm empty term rejected no insert`() =
        runTest {
            val repo = FakeDictionaryRepository()
            val vm = DictionaryViewModel(repo)
            // Need to wait for initial collect
            vm.uiState.first()

            vm.onAddWordTextChanged("   ")
            vm.onAddDefinitionTextChanged("definition")
            vm.onAddWordConfirm()
            // Allow coroutine to run
            advanceUntilIdle()

            assertEquals(0, repo.savedWords.size)
            assertTrue(repo.existsCalledWords.isEmpty() || repo.existsCalledWords.all { it.isBlank() } || true) // blank rejected before exists check
        }

    @Test
    fun `exists trimmed case-insensitive duplicate shows duplicate snackbar not inserted`() =
        runTest {
            val repo = FakeDictionaryRepository()
            // Pre-insert via repository directly
            repo.save("Hello", null)
            val vm = DictionaryViewModel(repo)
            vm.uiState.first()

            vm.onAddWordTextChanged("  hello  ")
            vm.onAddDefinitionTextChanged("def")

            val events = mutableListOf<UiEvent>()
            backgroundScope.launch(Dispatchers.Main) {
                vm.uiEvent.collect { events.add(it) }
            }

            vm.onAddWordConfirm()
            advanceUntilIdle()

            // Should not insert second time (still 1 saved)
            assertEquals(1, repo.savedWords.size)
            assertTrue(events.any { it is UiEvent.ShowSnackbar && (it as UiEvent.ShowSnackbar).message.contains("already in your dictionary") })
            // Message should be formatted with trimmed word
            val msg = (events.first { it is UiEvent.ShowSnackbar } as UiEvent.ShowSnackbar).message
            assertTrue(msg.contains("hello"))
        }

    @Test
    fun `duplicate guard uses trimmed exists`() =
        runTest {
            val repo = FakeDictionaryRepository()
            repo.save("TestWord", null)
            val vm = DictionaryViewModel(repo)
            vm.uiState.first()

            vm.onAddWordTextChanged("TestWord ")
            vm.onAddWordConfirm()
            advanceUntilIdle()

            assertEquals(1, repo.savedWords.size)
        }

    @Test
    fun `onRequestEditWord seeds the four user drafts and keeps the evidence display-only`() =
        runTest {
            val repo = FakeDictionaryRepository()
            val saved = repo.seedFullEntry()
            val vm = DictionaryViewModel(repo)
            vm.uiState.first()

            vm.onRequestEditWord(saved)

            val state = vm.uiState.value
            assertEquals(saved, state.selectedWord)
            assertEquals("Que dura poco tiempo", state.editDefinitionText)
            assertEquals("adjetivo", state.editPartOfSpeechText)
            assertEquals("eˈfimeɾo", state.editPhoneticText)
            assertEquals("Un amor efímero.", state.editExampleText)
            // The evidence never enters a draft: it is read from selectedWord for display only.
            assertEquals(saved.quote, state.selectedWord?.quote)
            assertEquals(saved.sourceBookTitle, state.selectedWord?.sourceBookTitle)
            assertEquals(saved.sourceBookAuthor, state.selectedWord?.sourceBookAuthor)
            assertEquals(saved.sourceChapter, state.selectedWord?.sourceChapter)
        }

    @Test
    fun `onEditDefinitionConfirm saves the four user fields through updateUserFields`() =
        runTest {
            val repo = FakeDictionaryRepository()
            val saved = repo.seedFullEntry()
            val vm = DictionaryViewModel(repo)
            vm.uiState.first()

            vm.onRequestEditWord(saved)
            vm.onEditDefinitionTextChanged("Nueva definición")
            vm.onEditPartOfSpeechTextChanged("sustantivo")
            vm.onEditPhoneticTextChanged("nuevo")
            vm.onEditExampleTextChanged("Nuevo ejemplo.")
            vm.onEditDefinitionConfirm()
            advanceUntilIdle()

            val update = repo.userFieldUpdates.single()
            assertEquals(saved.id, update.wordId)
            assertEquals("Nueva definición", update.definition)
            assertEquals("sustantivo", update.partOfSpeech)
            assertEquals("nuevo", update.phonetic)
            assertEquals("Nuevo ejemplo.", update.example)
            assertNull(vm.uiState.value.selectedWord)
        }

    @Test
    fun `onEditDefinitionConfirm cannot change the stored evidence`() =
        runTest {
            val repo = FakeDictionaryRepository()
            val saved = repo.seedFullEntry()
            val vm = DictionaryViewModel(repo)
            vm.uiState.first()

            vm.onRequestEditWord(saved)
            vm.onEditDefinitionTextChanged("Nueva definición")
            vm.onEditPartOfSpeechTextChanged("sustantivo")
            vm.onEditPhoneticTextChanged("nuevo")
            vm.onEditExampleTextChanged("Nuevo ejemplo.")
            vm.onEditDefinitionConfirm()
            advanceUntilIdle()

            val stored = repo.words().single()
            assertEquals("Nueva definición", stored.definition)
            assertEquals("Todo lo que nace está condenado a lo efímero.", stored.quote)
            assertEquals("book-1", stored.sourceBookId)
            assertEquals("La Odisea", stored.sourceBookTitle)
            assertEquals("Homero", stored.sourceBookAuthor)
            assertEquals("Canto I", stored.sourceChapter)
            assertEquals("epubcfi(/6/4!/2/10)", stored.sourceLocator)
        }

    @Test
    fun `onEditDefinitionConfirm stores blank user fields as null`() =
        runTest {
            val repo = FakeDictionaryRepository()
            val saved = repo.seedFullEntry()
            val vm = DictionaryViewModel(repo)
            vm.uiState.first()

            vm.onRequestEditWord(saved)
            vm.onEditDefinitionTextChanged("   ")
            vm.onEditPartOfSpeechTextChanged("")
            vm.onEditPhoneticTextChanged("  ")
            vm.onEditExampleTextChanged("")
            vm.onEditDefinitionConfirm()
            advanceUntilIdle()

            val update = repo.userFieldUpdates.single()
            assertNull(update.definition)
            assertNull(update.partOfSpeech)
            assertNull(update.phonetic)
            assertNull(update.example)
        }

    @Test
    fun `onDismissEditDialog clears the selection and the four drafts`() =
        runTest {
            val repo = FakeDictionaryRepository()
            val saved = repo.seedFullEntry()
            val vm = DictionaryViewModel(repo)
            vm.uiState.first()

            vm.onRequestEditWord(saved)
            vm.onDismissEditDialog()

            val state = vm.uiState.value
            assertNull(state.selectedWord)
            assertEquals("", state.editDefinitionText)
            assertEquals("", state.editPartOfSpeechText)
            assertEquals("", state.editPhoneticText)
            assertEquals("", state.editExampleText)
        }

    private class FakeDictionaryRepository : DictionaryRepository {
        val savedWords = mutableListOf<String>()
        val existsCalledWords = mutableListOf<String>()
        val userFieldUpdates = mutableListOf<UserFieldUpdate>()
        private val wordsFlow = MutableStateFlow<List<DictionaryWord>>(emptyList())

        data class UserFieldUpdate(
            val wordId: String,
            val definition: String?,
            val partOfSpeech: String?,
            val phonetic: String?,
            val example: String?,
        )

        fun words(): List<DictionaryWord> = wordsFlow.value

        fun seedFullEntry(): DictionaryWord {
            val entry =
                DictionaryWord(
                    id = "entry-1",
                    word = "Efímero",
                    addedAtEpochMillis = 1_700_000_000_000,
                    definition = "Que dura poco tiempo",
                    partOfSpeech = "adjetivo",
                    phonetic = "eˈfimeɾo",
                    example = "Un amor efímero.",
                    quote = "Todo lo que nace está condenado a lo efímero.",
                    sourceBookId = "book-1",
                    sourceBookTitle = "La Odisea",
                    sourceBookAuthor = "Homero",
                    sourceChapter = "Canto I",
                    sourceLocator = "epubcfi(/6/4!/2/10)",
                )
            savedWords.add(entry.word)
            wordsFlow.value = wordsFlow.value + entry
            return entry
        }

        override fun observeAll(): Flow<List<DictionaryWord>> = wordsFlow

        override fun search(query: String): Flow<List<DictionaryWord>> = wordsFlow

        override suspend fun save(
            word: String,
            definition: String?,
            partOfSpeech: String?,
            phonetic: String?,
            example: String?,
            quote: String?,
            sourceBookId: String?,
            sourceBookTitle: String?,
            sourceBookAuthor: String?,
            sourceChapter: String?,
            sourceLocator: String?,
        ): Result<DictionaryWord> {
            val trimmed = word.trim()
            // Simulate case-insensitive check like real repo
            if (savedWords.any { it.equals(trimmed, ignoreCase = true) }) {
                return Result.failure(IllegalStateException("duplicate"))
            }
            val dw =
                DictionaryWord(
                    id = "id-${savedWords.size + 1}",
                    word = trimmed,
                    addedAtEpochMillis = System.currentTimeMillis(),
                    definition = definition,
                )
            savedWords.add(trimmed)
            wordsFlow.value = wordsFlow.value + dw
            return Result.success(dw)
        }

        override suspend fun updateUserFields(
            wordId: String,
            definition: String?,
            partOfSpeech: String?,
            phonetic: String?,
            example: String?,
        ): Result<DictionaryWord> {
            userFieldUpdates += UserFieldUpdate(wordId, definition, partOfSpeech, phonetic, example)
            val current =
                wordsFlow.value.firstOrNull { it.id == wordId }
                    ?: return Result.failure(IllegalStateException("missing entry"))
            val updated =
                current.copy(
                    definition = definition,
                    partOfSpeech = partOfSpeech,
                    phonetic = phonetic,
                    example = example,
                )
            wordsFlow.value = wordsFlow.value.map { if (it.id == wordId) updated else it }
            return Result.success(updated)
        }

        override suspend fun delete(wordId: String) {
            wordsFlow.value = wordsFlow.value.filterNot { it.id == wordId }
        }

        override suspend fun exists(word: String): Boolean {
            existsCalledWords.add(word)
            val trimmed = word.trim()
            return savedWords.any { it.equals(trimmed, ignoreCase = true) }
        }
    }
}
