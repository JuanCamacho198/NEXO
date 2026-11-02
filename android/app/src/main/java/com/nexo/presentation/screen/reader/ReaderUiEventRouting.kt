package com.nexo.presentation.screen.reader

import com.nexo.presentation.UiEvent

/**
 * Maps a [UiEvent] to the message the reader's in-app snackbar host must show,
 * or `null` when the event belongs on another channel.
 *
 * The reader runs in immersive fullscreen, where a system `Toast` is drawn
 * behind the WebView surface and never reaches the user. [UiEvent.ShowSnackbar]
 * therefore has to go through the Compose `SnackbarHostState` — that is the
 * whole reason `ShowSnackbar` and [UiEvent.ShowToast] are separate events.
 *
 * `ShowToast` stays a system toast on purpose: it is used for transient
 * feedback that must survive independent of the Compose host.
 */
internal fun readerSnackbarMessage(event: UiEvent): String? =
    when (event) {
        is UiEvent.ShowSnackbar -> event.message
        else -> null
    }
