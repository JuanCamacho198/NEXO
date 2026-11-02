package com.nexo.presentation.screen.reader

import com.nexo.presentation.UiEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Routing contract for the reader's [UiEvent] stream.
 *
 * The reader runs in immersive fullscreen, where system `Toast`s are drawn
 * behind the WebView surface (or not at all). [UiEvent.ShowSnackbar] must
 * therefore reach the in-app [androidx.compose.material3.SnackbarHostState],
 * never a `Toast`. That distinction is the entire point of having two separate
 * event types, so it is pinned here instead of only in the composable.
 */
class ReaderUiEventRoutingTest {
    @Test
    fun showSnackbar_routesToSnackbarHost_notToToast() {
        assertEquals(
            "ShowSnackbar must reach the in-app snackbar host",
            "Copiado al portapapeles",
            readerSnackbarMessage(UiEvent.ShowSnackbar("Copiado al portapapeles")),
        )
    }

    @Test
    fun showSnackbar_withEmptyMessage_stillRoutes() {
        assertEquals("", readerSnackbarMessage(UiEvent.ShowSnackbar("")))
    }

    @Test
    fun showToast_doesNotRouteToSnackbarHost() {
        assertNull(
            "ShowToast keeps the system toast channel",
            readerSnackbarMessage(UiEvent.ShowToast("No text selected")),
        )
    }

    @Test
    fun shareText_doesNotRouteToSnackbarHost() {
        assertNull(readerSnackbarMessage(UiEvent.ShareText("some text")))
    }
}
