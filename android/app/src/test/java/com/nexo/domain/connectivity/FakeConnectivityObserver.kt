package com.nexo.domain.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test double for [ConnectivityObserver] wrapping a [MutableStateFlow], so
 * offline precedence, corroboration and resume-retry are testable on the JVM.
 */
class FakeConnectivityObserver(
    initiallyOnline: Boolean = true,
) : ConnectivityObserver {
    private val state = MutableStateFlow(initiallyOnline)

    override val isOnline: StateFlow<Boolean> = state.asStateFlow()

    override fun current(): Boolean = state.value

    fun setOnline(online: Boolean) {
        state.value = online
    }
}
