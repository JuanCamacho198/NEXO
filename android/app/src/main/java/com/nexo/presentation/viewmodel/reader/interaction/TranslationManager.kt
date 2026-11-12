package com.nexo.presentation.viewmodel.reader.interaction

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.nexo.R
import com.nexo.domain.repository.TranslationRepository
import com.nexo.domain.translation.NoTranslationReason
import com.nexo.domain.translation.TranslationLanguageSelection
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
 *
 * ## A language change is not a new selection (invariant 7)
 *
 * [onLanguageChanged] re-runs the translation for the selection ALREADY live in
 * the store — it never reads a new one and never closes the panel. That is the
 * whole difference between "the reader picked a language" and "the reader picked
 * a different quote": the first re-answers the same question, the second asks a
 * new one. Because the selection is re-read from the store at call time, a
 * language change that arrives while the reader has already moved on answers the
 * NEW selection — which is also correct, since only the newest request may paint.
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
     * translation. [sourceLanguage] is the resolved source; [targetLanguage] the
     * resolved target; [languages] is the pair+origin the bar renders, carried
     * from the SAME resolution so the bar and body cannot disagree.
     */
    fun onTranslate(
        sourceLanguage: String,
        targetLanguage: String,
        languages: TranslationLanguageSelection = TranslationLanguageSelection.EMPTY,
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
        // The panel still opens, reusing the same path as `onTranslationUnavailable`:
        // the reader gets an answer instead of a tap that seems to do nothing.
        // The reason is INVALID_SOURCE — there is no usable text to translate —
        // never the collision copy, which would claim the pair is the problem.
        if (!TranslationPolicy.isWorthTranslating(text)) {
            inFlight?.cancel()
            inFlight = null
            store.update {
                it.copy(
                    showTranslationPanel = true,
                    translationPanelState =
                        TranslationPanelState.NoTranslation(
                            reason = NoTranslationReason.INVALID_SOURCE,
                            languages = languages,
                        ),
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
                translationPanelState = TranslationPanelState.Loading(languages),
                showNoteModal = false,
                showTagInput = false,
                showDefinitionInput = false,
                showColorPickerPopover = false,
            )
        }
        val repo = translationRepository
        if (repo == null) {
            store.update {
                it.copy(
                    translationPanelState =
                        TranslationPanelState.Error(
                            message = appContext.getString(R.string.translation_no_result),
                            languages = languages,
                        ),
                )
            }
            return
        }
        inFlight?.cancel()
        inFlight =
            scope.launch(mainDispatcher) {
                val state =
                    when (val outcome = repo.translate(text, sourceLanguage, targetLanguage)) {
                        is TranslationOutcome.Translated ->
                            TranslationPanelState.Result(translated = outcome.text, languages = languages)
                        // The service answered with nothing usable (the `francés` →
                        // `francés` echo). NOT a pair collision — the pair resolved
                        // fine, so this is the genuinely missing translation and
                        // keeps the generic copy.
                        TranslationOutcome.NoTranslation ->
                            TranslationPanelState.NoTranslation(
                                reason = NoTranslationReason.UNKNOWN_SOURCE,
                                languages = languages,
                            )
                        TranslationOutcome.Offline ->
                            TranslationPanelState.Error(
                                message = appContext.getString(R.string.translation_offline),
                                languages = languages,
                            )
                        is TranslationOutcome.Error ->
                            TranslationPanelState.Error(message = outcome.message, languages = languages)
                    }
                store.update { it.copy(translationPanelState = state) }
            }
    }

    /**
     * Re-runs the translation for the SAME selection after the reader changed a
     * language in the bar (invariant 7).
     *
     * It is deliberately the same call [onTranslate] makes, with the new pair:
     * the manager already re-reads the live selection and already cancels the
     * previous request, so a language change needs no mechanism of its own. The
     * panel is NOT closed — the reader watches the same quote re-answer.
     *
     * When there is no usable selection, or no pair resolved, nothing is
     * re-run: a bar with no pair would have nothing to re-answer with, and
     * clearing a book's choice can legitimately resolve to a refusal.
     */
    fun onLanguageChanged(
        sourceLanguage: String,
        targetLanguage: String,
        languages: TranslationLanguageSelection,
    ) = onTranslate(sourceLanguage, targetLanguage, languages)

    /**
     * Opens the panel reporting that no translation is possible for this
     * selection, naming WHY.
     *
     * The panel is opened (not silently ignored) so the reader gets an answer
     * instead of a tap that seems to do nothing, and [reason] is carried all the
     * way from the resolver so the panel can name the cause instead of blaming
     * the text: a pair collision says the pair is the problem, which is the fix
     * the reader can actually make.
     *
     * [languages] keeps the bar visible next to the refusal, so the fix the copy
     * names is one tap away.
     */
    fun onTranslationUnavailable(
        reason: NoTranslationReason,
        languages: TranslationLanguageSelection = TranslationLanguageSelection.EMPTY,
    ) {
        val text =
            store.value.selectedText
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return
        inFlight?.cancel()
        inFlight = null
        store.update {
            it.copy(
                showTranslationPanel = true,
                translationPanelState =
                    TranslationPanelState.NoTranslation(
                        reason = reason,
                        languages = languages,
                    ),
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
