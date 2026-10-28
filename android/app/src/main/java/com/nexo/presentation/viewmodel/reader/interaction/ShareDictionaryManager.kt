package com.nexo.presentation.viewmodel.reader.interaction

import com.nexo.domain.repository.DictionaryRepository
import com.nexo.domain.session.ActiveUserProvider
import com.nexo.presentation.UiEvent
import com.nexo.presentation.viewmodel.reader.lifecycle.Clearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Share/copy intents and dictionary guard. Keeps isSuccess relaxed-MockK pattern.
 */
internal class ShareDictionaryManager(
    private val store: InteractionStateStore,
    private val selectionManager: SelectionManager,
    private val dictionaryRepository: DictionaryRepository?,
    private val activeUser: ActiveUserProvider,
    private val scope: CoroutineScope,
    private val onEvent: (UiEvent) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Clearable {
    fun onShareSelectedText(selectedText: String?) {
        val text = selectedText
        if (text.isNullOrBlank()) {
            onEvent(UiEvent.ShowToast("No text selected"))
            return
        }
        onEvent(UiEvent.ShareText(text))
        selectionManager.dismissMenuAndClearSelection()
    }

    fun onCopySelectedText() {
        if (store.value.selectedText == null) return
        onEvent(UiEvent.ShowSnackbar("Copiado al portapapeles"))
        selectionManager.dismissMenuAndClearSelection()
    }

    fun onAddToDictionary() {
        val repo = dictionaryRepository ?: return
        val text =
            store.value.selectedText
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return
        scope.launch(mainDispatcher) {
            val userId = activeUser.requireUserId()
            if (repo.exists(text, userId)) {
                onEvent(UiEvent.ShowSnackbar("\"$text\" ya está en tu diccionario"))
            } else {
                val result = repo.save(text, userId)
                if (result.isSuccess) {
                    onEvent(UiEvent.ShowSnackbar("\"$text\" guardada en tu diccionario"))
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Error al guardar"
                    onEvent(UiEvent.ShowSnackbar(msg))
                }
            }
        }
        selectionManager.dismissMenuAndClearSelection()
    }

    fun onSaveDefinition(definition: String) {
        val repo = dictionaryRepository ?: return
        val text = store.value.selectedText?.trim()
        if (text.isNullOrBlank()) return
        val trimmedDefinition = definition.trim().takeIf { it.isNotBlank() }
        scope.launch(mainDispatcher) {
            val userId = activeUser.requireUserId()
            if (repo.exists(text, userId)) {
                onEvent(UiEvent.ShowSnackbar("Already in dictionary"))
            } else {
                repo.save(text, userId, trimmedDefinition).fold(
                    onSuccess = { onEvent(UiEvent.ShowSnackbar("Added to dictionary")) },
                    onFailure = { e -> onEvent(UiEvent.ShowSnackbar(e.message ?: "Failed to add to dictionary")) },
                )
            }
        }
        selectionManager.dismissMenuAndClearSelection()
    }

    override fun onCleared() {}
}
