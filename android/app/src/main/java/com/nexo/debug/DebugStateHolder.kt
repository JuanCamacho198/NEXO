package com.nexo.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds real-time debug state surfaced by the [DebugPanel].
 *
 * - [actionMode] tracks the activity-level [android.view.ActionMode] lifecycle.
 * - [highlight] tracks the [org.readium.r2.navigator.DecorableNavigator.Listener]
 *   callback registration and invocations.
 */
data class DebugActionModeState(
    val installed: Boolean = false,
    val lastEvent: String = "—",
    val lastType: String = "—",
    val suppressedCount: Int = 0,
)

data class DebugHighlightState(
    val listenerRegistered: Boolean = false,
    val lastEventId: String = "—",
    val lastEventRect: String = "—",
    val activationCount: Int = 0,
)

data class DebugDecorationState(
    val listenerRegistered: Boolean = false,
    val activationCount: Int = 0,
    val lastEventId: String = "—",
    val lastEventRect: String = "—",
    val lastEventGroup: String = "—",
    val lastAppliedCount: Int = 0,
    val activeCount: Int = 0,
)

object DebugStateHolder {
    private val _actionMode = MutableStateFlow(DebugActionModeState())
    val actionMode: StateFlow<DebugActionModeState> = _actionMode.asStateFlow()

    private val _highlight = MutableStateFlow(DebugHighlightState())
    val highlight: StateFlow<DebugHighlightState> = _highlight.asStateFlow()

    private val _decoration = MutableStateFlow(DebugDecorationState())
    val decoration: StateFlow<DebugDecorationState> = _decoration.asStateFlow()

    fun setActionModeInstalled(installed: Boolean) {
        _actionMode.update { it.copy(installed = installed) }
    }

    fun recordActionModeEvent(
        event: String,
        type: String,
    ) {
        _actionMode.update {
            it.copy(
                lastEvent = event,
                lastType = type,
                suppressedCount = it.suppressedCount + 1,
            )
        }
    }

    fun setHighlightListenerRegistered(registered: Boolean) {
        _highlight.update { it.copy(listenerRegistered = registered) }
    }

    fun recordHighlightActivation(
        id: String,
        rect: String,
    ) {
        _highlight.update {
            it.copy(
                lastEventId = id,
                lastEventRect = rect,
                activationCount = it.activationCount + 1,
            )
        }
    }

    fun setListenerRegistered(registered: Boolean) {
        _decoration.update { it.copy(listenerRegistered = registered) }
    }

    fun recordDecorationEvent(
        id: String,
        group: String,
        rect: android.graphics.RectF?,
    ) {
        val rectString =
            rect?.let {
                "[${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}]"
            } ?: "—"
        _decoration.update {
            it.copy(
                activationCount = it.activationCount + 1,
                lastEventId = id,
                lastEventGroup = group,
                lastEventRect = rectString,
            )
        }
        DebugLog.success("Decoration", "Activated: id=$id, group=$group, rect=$rect")
    }

    fun recordApplied(count: Int) {
        _decoration.update {
            it.copy(
                lastAppliedCount = count,
                activeCount = count,
            )
        }
    }
}
