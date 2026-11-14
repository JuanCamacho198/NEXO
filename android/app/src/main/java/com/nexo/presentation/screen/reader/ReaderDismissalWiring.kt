package com.nexo.presentation.screen.reader

import com.nexo.presentation.viewmodel.ReaderViewModel

/**
 * The mapping from a reader floating SURFACE to the ViewModel method that
 * dismisses it.
 *
 * It exists as a separate, non-composable object for one reason: this mapping is
 * exactly what was wrong, and while it lived inline in `remember` blocks inside
 * a `@Composable` it could not be reached by any JVM test. Two surfaces whose
 * names differ by one word — the definition PANEL and the dictionary-save
 * INPUT — had their dismiss callbacks crossed, so the panel's "Close" cleared
 * the input's flag and the panel never closed. The manager was correct and
 * tested; the WIRING was dead and untestable.
 *
 * Keeping the two dismissals as separate, named functions here makes the
 * cross-over impossible to write by accident and possible to test: a test can
 * drive `dismissDefinitionPanel` on a real [ReaderViewModel] and assert that
 * `showDefinitionPanel` — and ONLY that flag — goes false.
 *
 * The composable that builds [ReaderSelectionCallbacks] reads these; no other
 * place may re-derive them.
 */
object ReaderDismissalWiring {
    /**
     * "Close" on the definition-lookup card. Clears `showDefinitionPanel` and
     * keeps the selection so the menu can return.
     */
    fun dismissDefinitionPanel(viewModel: ReaderViewModel): () -> Unit = { viewModel.onDismissDefinitionPanel() }

    /**
     * "Cancel" on the save-to-personal-dictionary card. Clears
     * `showDefinitionInput` — a DIFFERENT surface from the lookup panel.
     */
    fun dismissDefinitionInput(viewModel: ReaderViewModel): () -> Unit =
        {
            viewModel.interactionHolder.onDismissDefinitionInput()
        }
}
