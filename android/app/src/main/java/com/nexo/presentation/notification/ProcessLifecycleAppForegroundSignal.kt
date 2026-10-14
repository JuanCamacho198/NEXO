package com.nexo.presentation.notification

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nexo.domain.notification.AppForegroundSignal
import com.nexo.domain.notification.MutableAppForegroundSignal
import kotlinx.coroutines.flow.StateFlow

/**
 * AndroidX-backed [AppForegroundSignal] over [ProcessLifecycleOwner].
 *
 * `ProcessLifecycleOwner` is the process-wide lifecycle (not a single
 * Activity), so ON_START / ON_STOP track "is any part of the app on screen"
 * and survive rotation and Activity hand-off. The observer is registered at
 * construction and never removed: the holder lives for the whole process, so
 * removing it would only ever be a teardown of a singleton.
 *
 * Unknown state stays [AppForegroundSignal.DEFAULT_IS_FOREGROUND] (true) until
 * the first ON_START/ON_STOP — see the interface KDoc for why that is the safe
 * default.
 */
class ProcessLifecycleAppForegroundSignal private constructor(
    private val delegate: MutableAppForegroundSignal,
) : AppForegroundSignal {
    override val isForeground: StateFlow<Boolean> = delegate.isForeground

    private val observer =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                delegate.set(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                delegate.set(false)
            }
        }

    private fun attach() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
    }

    companion object {
        /** Builds the signal and starts observing the process lifecycle. */
        fun install(): ProcessLifecycleAppForegroundSignal {
            val signal = ProcessLifecycleAppForegroundSignal(MutableAppForegroundSignal.create())
            signal.attach()
            return signal
        }
    }
}
