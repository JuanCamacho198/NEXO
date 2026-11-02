package com.nexo.presentation.viewmodel.reader.interaction

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.nexo.R
import com.nexo.domain.repository.TranslationRepository
import com.nexo.domain.translation.TranslationOutcome
import com.nexo.presentation.viewmodel.reader.TranslationPanelState
import com.nexo.presentation.viewmodel.reader.lifecycle.Clearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Anchored translation of the current selection (MyMemory, cache-first).
 *
 * Reads the live `selectedText` at call time — never a captured composition
 * value — and resolves the target language from the caller (app/session
 * locale). Owns no selection state beyond the translation panel flags.
 *
 * The panel opens immediately in [TranslationPanelState.Loading]; the request
 * result then swaps in Result / NoTranslation / Error. A new selection
 * cancels any in-flight request (only the newest one may paint the panel).
 */
internal class TranslationManager(
    private val store: InteractionStateStore,
    private val translationRepository: TranslationRepository?,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Clearable {
    private var inFlight: Job? = null

    /**
     * Opens the translation panel for the current selection and requests a
     * translation. [targetLanguage] is the app/session language.
     */
    fun onTranslate(
        sourceLanguage: String,
        targetLanguage: String,
    ) {
        val text =
            store.value.selectedText
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return
        store.update {
            it.copy(
                showTranslationPanel = true,
                translationPanelState = TranslationPanelState.Loading,
                showNoteModal = false,
                showTagInput = false,
                showDefinitionInput = false,
                showColorPickerPopover = false,
            )
        }
        val repo = translationRepository
        if (repo == null) {
            store.update {
                it.copy(translationPanelState = TranslationPanelState.Error(appContext.getString(R.string.translation_no_result)))
            }
            return
        }
        inFlight?.cancel()
        inFlight =
            scope.launch(mainDispatcher) {
                val state =
                    when (val outcome = repo.translate(text, sourceLanguage, targetLanguage)) {
                        is TranslationOutcome.Translated -> TranslationPanelState.Result(outcome.text)
                        TranslationOutcome.NoTranslation -> TranslationPanelState.NoTranslation
                        TranslationOutcome.Offline -> TranslationPanelState.Error(appContext.getString(R.string.translation_offline))
                        is TranslationOutcome.Error -> TranslationPanelState.Error(outcome.message)
                    }
                store.update { it.copy(translationPanelState = state) }
            }
    }

    /** Closes the panel but keeps the selection so the menu can return. */
    fun onDismissTranslationPanel() {
        inFlight?.cancel()
        inFlight = null
        store.update {
            it.copy(
                showTranslationPanel = false,
                translationPanelState = TranslationPanelState.Idle,
            )
        }
    }

    @VisibleForTesting
    internal fun hasInFlight() = inFlight?.isActive == true

    override fun onCleared() {
        inFlight?.cancel()
        inFlight = null
    }
}
