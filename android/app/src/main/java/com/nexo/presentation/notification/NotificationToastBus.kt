package com.nexo.presentation.notification

import com.nexo.presentation.UiEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide channel for notification toasts (U7).
 *
 * The notification store is app-scoped (it must be reachable from workers and
 * deep-link entry points), but the toast is a UI event. This bus decouples
 * them: the store's [com.nexo.domain.notification.ToastSink] publishes a
 * [UiEvent.ShowToast] here, and the nav host collects and presents it through
 * the same snackbar host the rest of the app uses.
 *
 * `extraBufferCapacity = 1` so a burst of two emissions does not silently drop
 * one, matching the other one-shot event flows in this module.
 */
class NotificationToastBus {
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)

    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun publish(event: UiEvent) {
        _events.tryEmit(event)
    }
}
