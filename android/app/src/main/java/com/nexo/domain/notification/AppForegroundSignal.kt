package com.nexo.domain.notification

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the app process is currently in the foreground.
 *
 * The signal is process-wide (not per-Activity) so a rotation or an Activity
 * hand-off never flips it. When the real lifecycle state is UNKNOWN the signal
 * MUST report [DEFAULT_IS_FOREGROUND] = true: `interruption == SYSTEM` only
 * posts to the OS surface while the app is backgrounded, so an unknown reading
 * must not suppress an OS notification that the user was meant to receive — and
 * must never allow one while the user is actually looking at the app. Treating
 * "unknown" as foreground is the safe side of that asymmetry (worst case: one
 * OS notification is dropped while the app is in fact backgrounded, never a
 * spurious banner over the app the user is using).
 */
interface AppForegroundSignal {
    val isForeground: StateFlow<Boolean>

    companion object {
        const val DEFAULT_IS_FOREGROUND: Boolean = true
    }
}

/**
 * Injectable, settable [AppForegroundSignal]. The Android adapter
 * (`ProcessLifecycleAppForegroundSignal`) writes to it from
 * `ProcessLifecycleOwner`; tests write to it directly. Starts at
 * [AppForegroundSignal.DEFAULT_IS_FOREGROUND].
 */
class MutableAppForegroundSignal private constructor() : AppForegroundSignal {
    private val _isForeground = kotlinx.coroutines.flow.MutableStateFlow(AppForegroundSignal.DEFAULT_IS_FOREGROUND)
    override val isForeground: StateFlow<Boolean> = _isForeground

    fun set(value: Boolean) {
        _isForeground.value = value
    }

    companion object {
        fun create(): MutableAppForegroundSignal = MutableAppForegroundSignal()
    }
}
