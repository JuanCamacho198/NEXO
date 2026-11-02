package com.nexo.presentation.viewmodel.reader.interaction

import android.content.Context
import com.nexo.R
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
    private val appContext: Context,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Clearable {
    fun onShareSelectedText(selectedText: String?) {
        val text = selectedText
        if (text.isNullOrBlank()) {
            onEvent(UiEvent.ShowToast(appContext.getString(R.string.reader_selection_no_text)))
            return
        }
        onEvent(UiEvent.ShareText(text))
        selectionManager.dismissMenuAndClearSelection()
    }

    fun onCopySelectedText() {
        if (store.value.selectedText == null) return
        onEvent(UiEvent.ShowSnackbar(appContext.getString(R.string.highlights_snackbar_copied)))
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
                onEvent(
                    UiEvent.ShowSnackbar(appContext.getString(R.string.dictionary_duplicate, text)),
                )
            } else {
                val result = repo.save(text, userId)
                if (result.isSuccess) {
                    onEvent(
                        UiEvent.ShowSnackbar(appContext.getString(R.string.dictionary_added, text)),
                    )
                } else {
                    val msg =
                        result.exceptionOrNull()?.message
                            ?: appContext.getString(R.string.reader_snackbar_save_error)
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
                onEvent(
                    UiEvent.ShowSnackbar(appContext.getString(R.string.dictionary_duplicate, text)),
                )
            } else {
                repo.save(text, userId, trimmedDefinition).fold(
                    onSuccess = {
                        onEvent(
                            UiEvent.ShowSnackbar(
                                appContext.getString(R.string.highlights_snackbar_added_to_dictionary),
                            ),
                        )
                    },
                    onFailure = { e ->
                        onEvent(
                            UiEvent.ShowSnackbar(
                                e.message
                                    ?: appContext.getString(R.string.highlights_snackbar_add_to_dictionary_failed),
                            ),
                        )
                    },
                )
            }
        }
        selectionManager.dismissMenuAndClearSelection()
    }

    override fun onCleared() {}
}
