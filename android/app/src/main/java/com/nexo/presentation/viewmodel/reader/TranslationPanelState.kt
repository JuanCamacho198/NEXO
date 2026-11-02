package com.nexo.presentation.viewmodel.reader

/**
 * State of the anchored translation panel for the current selection.
 *
 * [Idle] means no translation is in flight and the panel is not shown; the
 * remaining states drive the panel body. Mirrors the shape used by the other
 * interaction surfaces (hoisted state, no side effects).
 */
sealed interface TranslationPanelState {
    data object Idle : TranslationPanelState

    data object Loading : TranslationPanelState

    data class Result(
        val translated: String,
    ) : TranslationPanelState

    /**
     * The upstream echoed the source back (the `francés` → `francés` defect
     * with match 0.99) or produced nothing usable. Shown as a muted message
     * instead of a misleading identical string.
     */
    data object NoTranslation : TranslationPanelState

    data class Error(
        val message: String,
    ) : TranslationPanelState
}
