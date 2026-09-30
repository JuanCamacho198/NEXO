package com.nexo.presentation.viewmodel.reader.lifecycle

import com.nexo.presentation.UiEvent
import org.readium.r2.shared.publication.Locator

/**
 * Side effects emitted by lifecycle collaborators.
 * Breaks ViewModel -> Holder -> ViewModel cycle by routing via interfaces/flows
 * instead of direct ViewModel references.
 */
sealed interface ReaderSideEffect {
    data class NavigateToLocator(
        val locator: Locator,
    ) : ReaderSideEffect

    data class ShowError(
        val message: String,
    ) : ReaderSideEffect
}

interface NavigatorCallbacks {
    fun onChapterChanged()

    fun onNavigateToLocator(locator: Locator)

    fun onSelectionCleared()

    fun onBookLoaded(bookId: String)
}

interface ProgressCallbacks {
    fun onErrorEvent(event: UiEvent)
}
