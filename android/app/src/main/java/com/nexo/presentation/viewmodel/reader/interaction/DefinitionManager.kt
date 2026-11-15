package com.nexo.presentation.viewmodel.reader.interaction

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.nexo.R
import com.nexo.domain.definition.DefinableSelection
import com.nexo.domain.definition.DefinitionRequest
import com.nexo.domain.definition.DefinitionResult
import com.nexo.domain.definition.WordLanguage
import com.nexo.domain.repository.DefinitionRepository
import com.nexo.presentation.viewmodel.reader.DefinitionPanelState
import com.nexo.presentation.viewmodel.reader.lifecycle.Clearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Anchored definition of the current selection (Wiktionary cascade,
 * cache-first).
 *
 * Reads the live `selectedText` at call time — never a captured composition
 * value — and takes the reader's definition language and the word's language as
 * parameters, resolved by the caller (the ViewModel owns the book and the
 * preference store). Owns no selection state beyond the definition panel flags.
 *
 * ## The two languages are NEVER conflated (invariant 1)
 *
 * [readerLanguage] is what the reader wants to read definitions in;
 * [wordLanguage] is a fact about the book. They travel as separate parameters
 * and are stored on separate [DefinitionRequest] fields, so a caller cannot
 * pass one where the other belongs.
 *
 * ## A real lookup is gated by [DefinableSelection] (T6)
 *
 * A selection that is not a candidate (a sentence, `123`, an ellipsis) never
 * reaches the network: the panel opens stating that nothing was found, reusing
 * the empty branch, rather than spending three requests to say a paragraph is a
 * paragraph.
 *
 * ## A definition-language change re-runs the lookup (invariant, ODD T8)
 *
 * [onLanguageChanged] re-runs the lookup for the selection ALREADY live in the
 * store — it never reads a new one and never closes the panel, exactly as
 * [TranslationManager.onLanguageChanged] does for translation.
 */
internal class DefinitionManager(
    private val store: InteractionStateStore,
    private val definitionRepository: DefinitionRepository?,
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : Clearable {
    private var inFlight: Job? = null

    /**
     * Opens the definition panel for the current selection and requests a
     * lookup.
     *
     * @param readerLanguage the language definitions must be written in.
     * @param wordLanguage the language of the word, from the book.
     */
    fun onDefine(
        readerLanguage: String,
        wordLanguage: WordLanguage,
    ) {
        val raw =
            store.value.selectedText
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return

        // The word the reader meant, not the marks the drag happened to cover:
        // `"de"` is `de`, `efectismos.` is `efectismos`. Trimming here as well as
        // in the gate is deliberate — the gate decides WHETHER to look up, this
        // decides WHAT, and both must derive the word the same way or a selection
        // the gate admits would still be sent with its quotes (Defect B).
        val text = DefinableSelection.trimSurroundingPunctuation(raw)

        // T6 gate: a non-candidate never spends a request. The panel still
        // opens — the reader gets an answer instead of a tap that does nothing —
        // reusing the honest empty branch, since a paragraph genuinely has no
        // headword in any dictionary.
        if (!DefinableSelection.isDefinable(text)) {
            inFlight?.cancel()
            inFlight = null
            store.update {
                it.copy(
                    showDefinitionPanel = true,
                    definitionPanelState =
                        DefinitionPanelState.Empty(
                            word = text,
                            wordLanguage = wordLanguage.wordLanguage,
                            wordLanguageKnown = wordLanguage.known,
                        ),
                    definitionLanguage = readerLanguage,
                    definitionWord = text,
                    showTranslationPanel = false,
                    showNoteModal = false,
                    showTagInput = false,
                    showDefinitionInput = false,
                    showColorPickerPopover = false,
                )
            }
            return
        }

        store.update {
            it.copy(
                showDefinitionPanel = true,
                definitionPanelState =
                    DefinitionPanelState.Loading(
                        wordLanguage = wordLanguage.wordLanguage,
                        wordLanguageKnown = wordLanguage.known,
                    ),
                definitionLanguage = readerLanguage,
                definitionWord = text,
                showTranslationPanel = false,
                showNoteModal = false,
                showTagInput = false,
                showDefinitionInput = false,
                showColorPickerPopover = false,
            )
        }
        val repo = definitionRepository
        if (repo == null) {
            store.update {
                it.copy(
                    definitionPanelState =
                        DefinitionPanelState.Failure(
                            message = appContext.getString(R.string.definition_no_service),
                            wordLanguage = wordLanguage.wordLanguage,
                            wordLanguageKnown = wordLanguage.known,
                        ),
                )
            }
            return
        }
        inFlight?.cancel()
        inFlight =
            scope.launch(mainDispatcher) {
                val request =
                    DefinitionRequest(
                        word = text,
                        readerLanguage = readerLanguage,
                        wordLanguage = wordLanguage,
                    )
                val state = repo.define(request).toPanelState()
                store.update { it.copy(definitionPanelState = state) }
            }
    }

    /**
     * Re-runs the lookup for the SAME selection after the reader changed the
     * definition-language selector. The panel is NOT closed. Deliberately the
     * same call [onDefine] makes, with the new reader language.
     */
    fun onLanguageChanged(
        readerLanguage: String,
        wordLanguage: WordLanguage,
    ) = onDefine(readerLanguage, wordLanguage)

    /** Closes the panel but keeps the selection so the menu can return. */
    fun onDismissDefinitionPanel() {
        inFlight?.cancel()
        inFlight = null
        store.update {
            it.copy(
                showDefinitionPanel = false,
                definitionPanelState = DefinitionPanelState.Idle,
                definitionWord = null,
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

/**
 * Maps the repository's result onto the panel state, keeping the two failure
 * truths apart: a [DefinitionResult.failure] is a service failure, an empty
 * `definitions` with no failure is the honest "no definition" (ODD invariant 2).
 */
private fun DefinitionResult.toPanelState(): DefinitionPanelState {
    // Local val: `failure` is a property declared in :core:domain, so the
    // cross-module boundary blocks the implicit smart cast after the null check.
    val currentFailure = failure
    return when {
        currentFailure != null ->
            DefinitionPanelState.Failure(
                message = currentFailure.message,
                wordLanguage = wordLanguage,
                wordLanguageKnown = wordLanguageKnown,
            )
        definitions.isNotEmpty() ->
            DefinitionPanelState.Result(
                result = this,
                wordLanguage = wordLanguage,
                wordLanguageKnown = wordLanguageKnown,
            )
        else ->
            DefinitionPanelState.Empty(
                word = word,
                wordLanguage = wordLanguage,
                wordLanguageKnown = wordLanguageKnown,
            )
    }
}
