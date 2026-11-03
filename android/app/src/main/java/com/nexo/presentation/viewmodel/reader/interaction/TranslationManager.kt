package com.nexo.presentation.viewmodel.reader.interaction

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.nexo.R
import com.nexo.domain.repository.TranslationRepository
import com.nexo.domain.translation.TranslationOutcome
import com.nexo.domain.translation.TranslationPolicy
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

        // T3 of `translation-guards-and-cache-pruning`: a selection that is not
        // worth translating must not spend a request — and, because the cache
        // write lives behind the request, it writes no row either. Measured
        // against the live API, `.` came back as `bility` and `«` as `&laquo;`;
        // both changed, so the output guard cannot catch them. The input guard
        // is the only place that can.
        //
        // The panel still opens, in NoTranslation, reusing the same path as
        // `onTranslationUnavailable`: the reader gets an answer instead of a
        // tap that seems to do nothing, and reusing the state avoids a new
        // string in two languages.
        if (!TranslationPolicy.isWorthTranslating(text)) {
            inFlight?.cancel()
            inFlight = null
            store.update {
                it.copy(
                    showTranslationPanel = true,
                    translationPanelState = TranslationPanelState.NoTranslation,
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

    /**
     * Opens the panel reporting that no translation is possible for this
     * selection — the resolved source equals the resolved target, so any request
     * would only echo the text back. The panel is opened (not silently ignored)
     * so the reader gets an answer instead of a tap that seems to do nothing.
     */
    fun onTranslationUnavailable() {
        val text =
            store.value.selectedText
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return
        inFlight?.cancel()
        inFlight = null
        store.update {
            it.copy(
                showTranslationPanel = true,
                translationPanelState = TranslationPanelState.NoTranslation,
                showNoteModal = false,
                showTagInput = false,
                showDefinitionInput = false,
                showColorPickerPopover = false,
            )
        }
    }

    /**
     * Session boundary for cache retention (T4 of
     * `translation-guards-and-cache-pruning`).
     *
     * The sweep runs here — once when a book is opened — rather than on every
     * translation. A per-request sweep would add a write to the hot path to
     * reclaim rows that are not yet a problem; a book open is frequent enough
     * that the cap is enforced in practice and rare enough that it costs
     * nothing. The repository owns the cap and the account scoping; a missing
     * repository (a holder built without one) simply skips the sweep.
     */
    fun onBookOpened() {
        val repo = translationRepository ?: return
        scope.launch(mainDispatcher) { repo.pruneCache() }
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
