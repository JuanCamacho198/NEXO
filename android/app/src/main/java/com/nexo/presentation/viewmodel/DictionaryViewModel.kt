package com.nexo.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexo.domain.model.DictionaryWord
import com.nexo.domain.repository.DictionaryRepository
import com.nexo.presentation.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DictionaryUiState(
    val words: List<DictionaryWord> = emptyList(),
    val searchQuery: String = "",
    val filteredWords: List<DictionaryWord> = emptyList(),
    val isLoading: Boolean = true,
    val wordToDelete: DictionaryWord? = null,
    val showAddDialog: Boolean = false,
    val addWordText: String = "",
    val addDefinitionText: String = "",
    val selectedWord: DictionaryWord? = null,
    val editDefinitionText: String = "",
    val editPartOfSpeechText: String = "",
    val editPhoneticText: String = "",
    val editExampleText: String = "",
)

class DictionaryViewModel(
    private val dictionaryRepository: DictionaryRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DictionaryUiState())
    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val searchQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                dictionaryRepository.observeAll(),
                searchQuery,
            ) { words, query ->
                val filtered =
                    if (query.isBlank()) {
                        words
                    } else {
                        words.filter { it.word.contains(query, ignoreCase = true) }
                    }
                _uiState.update {
                    it.copy(
                        words = words,
                        searchQuery = query,
                        filteredWords = filtered,
                        isLoading = false,
                    )
                }
            }.collect { }
        }
    }

    fun onSearchQueryChanged(query: String) {
        searchQuery.update { query }
    }

    fun onShowAddDialog() {
        _uiState.update { it.copy(showAddDialog = true, addWordText = "", addDefinitionText = "") }
    }

    fun onDismissAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, addWordText = "", addDefinitionText = "") }
    }

    fun onAddWordTextChanged(text: String) {
        _uiState.update { it.copy(addWordText = text) }
    }

    fun onAddDefinitionTextChanged(text: String) {
        _uiState.update { it.copy(addDefinitionText = text) }
    }

    fun onAddWordConfirm() {
        val trimmed = _uiState.value.addWordText.trim()
        if (trimmed.isBlank()) return
        val definition =
            _uiState.value.addDefinitionText
                .trim()
                .takeIf { it.isNotBlank() }

        viewModelScope.launch {
            if (dictionaryRepository.exists(trimmed)) {
                _uiEvent.emit(
                    UiEvent.ShowSnackbar(
                        "\"$trimmed\" is already in your dictionary.",
                    ),
                )
            } else {
                dictionaryRepository.save(trimmed, definition).fold(
                    onSuccess = {
                        _uiEvent.emit(
                            UiEvent.ShowSnackbar(
                                "\"$trimmed\" added to your dictionary.",
                            ),
                        )
                    },
                    onFailure = { e ->
                        _uiEvent.emit(
                            UiEvent.ShowSnackbar(
                                e.message ?: "Failed to add word",
                            ),
                        )
                    },
                )
            }
            _uiState.update { it.copy(showAddDialog = false, addWordText = "", addDefinitionText = "") }
        }
    }

    fun onRequestDeleteWord(word: DictionaryWord) {
        _uiState.update { it.copy(wordToDelete = word) }
    }

    fun onDismissDeleteDialog() {
        _uiState.update { it.copy(wordToDelete = null) }
    }

    fun onConfirmDeleteWord() {
        val word = _uiState.value.wordToDelete ?: return
        viewModelScope.launch {
            dictionaryRepository.delete(word.id)
            _uiState.update { it.copy(wordToDelete = null) }
        }
    }

    // ── Detail / edit ────────────────────────────────────────────

    /**
     * Opens the detail dialog for [word], pre-filling the four user-authored drafts. The six evidence
     * fields are read from [word] for display only; they are never drafted or saved here.
     */
    fun onRequestEditWord(word: DictionaryWord) {
        _uiState.update {
            it.copy(
                selectedWord = word,
                editDefinitionText = word.definition.orEmpty(),
                editPartOfSpeechText = word.partOfSpeech.orEmpty(),
                editPhoneticText = word.phonetic.orEmpty(),
                editExampleText = word.example.orEmpty(),
            )
        }
    }

    fun onDismissEditDialog() {
        _uiState.update {
            it.copy(
                selectedWord = null,
                editDefinitionText = "",
                editPartOfSpeechText = "",
                editPhoneticText = "",
                editExampleText = "",
            )
        }
    }

    fun onEditDefinitionTextChanged(text: String) {
        _uiState.update { it.copy(editDefinitionText = text) }
    }

    fun onEditPartOfSpeechTextChanged(text: String) {
        _uiState.update { it.copy(editPartOfSpeechText = text) }
    }

    fun onEditPhoneticTextChanged(text: String) {
        _uiState.update { it.copy(editPhoneticText = text) }
    }

    fun onEditExampleTextChanged(text: String) {
        _uiState.update { it.copy(editExampleText = text) }
    }

    /**
     * Saves the four user-authored fields through [DictionaryRepository.updateUserFields], which
     * cannot address an evidence column. The selected entry's quote and book reference are left
     * untouched by construction (REQ-DRE-007).
     */
    fun onEditDefinitionConfirm() {
        val word = _uiState.value.selectedWord ?: return
        val state = _uiState.value
        viewModelScope.launch {
            dictionaryRepository
                .updateUserFields(
                    wordId = word.id,
                    definition = state.editDefinitionText.trimmedOrNull(),
                    partOfSpeech = state.editPartOfSpeechText.trimmedOrNull(),
                    phonetic = state.editPhoneticText.trimmedOrNull(),
                    example = state.editExampleText.trimmedOrNull(),
                ).fold(
                    onSuccess = {
                        _uiEvent.emit(UiEvent.ShowSnackbar("Definición guardada"))
                        _uiState.update {
                            it.copy(
                                selectedWord = null,
                                editDefinitionText = "",
                                editPartOfSpeechText = "",
                                editPhoneticText = "",
                                editExampleText = "",
                            )
                        }
                    },
                    onFailure = { e ->
                        _uiEvent.emit(
                            UiEvent.ShowSnackbar(
                                e.message ?: "Failed to save word",
                            ),
                        )
                    },
                )
        }
    }

    private fun String.trimmedOrNull(): String? = trim().takeIf { it.isNotBlank() }
}
