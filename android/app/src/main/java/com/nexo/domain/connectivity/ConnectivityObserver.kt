package com.nexo.domain.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Domain port exposing the device network availability.
 *
 * Consumers (ViewModels) collect [isOnline] to drive pre-emptive offline UI;
 * the data layer provides the Android-backed implementation.
 */
interface ConnectivityObserver {
    /** Hot, conflated flow that emits on every network availability change. */
    val isOnline: StateFlow<Boolean>

    /** Current availability without collecting [isOnline]. */
    fun current(): Boolean
}

/**
 * Always-online default used by previews and by unit tests that do not care
 * about connectivity. Never emits a change.
 */
object AlwaysOnlineConnectivityObserver : ConnectivityObserver {
    private val online = MutableStateFlow(true)

    override val isOnline: StateFlow<Boolean> = online.asStateFlow()

    override fun current(): Boolean = true
}
