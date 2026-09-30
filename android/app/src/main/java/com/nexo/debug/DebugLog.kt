package com.nexo.debug

import android.util.Log
import io.sentry.Sentry
import io.sentry.SentryLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-memory debug event log + Logcat sink.
 *
 * - Always available (no [BuildConfig.DEBUG] gate). This keeps call sites
 *   free of `if (BuildConfig.DEBUG)` checks; the visibility of the
 *   debug UI is the only runtime gate.
 * - Thread-safe: mutations go through [MutableStateFlow.update].
 * - Capped at [MAX_EVENTS] entries (oldest dropped).
 * - A separate [errorEvents] ring buffer only stores `ERROR`-level events
 *   so critical failures are not evicted by high-frequency INFO/DEBUG
 *   messages (e.g. the selection poll every 300 ms). Its capacity is
 *   [MAX_ERROR_EVENTS].
 */
object DebugLog {
    enum class Level { INFO, WARN, ERROR, SUCCESS }

    data class DebugEvent(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val MAX_EVENTS = 500
    private const val MAX_ERROR_EVENTS = 200

    private val _events = MutableStateFlow<List<DebugEvent>>(emptyList())
    val events: StateFlow<List<DebugEvent>> = _events.asStateFlow()

    private val _errorEvents = MutableStateFlow<List<DebugEvent>>(emptyList())

    /** Only `ERROR`-level events, newest first. Survives INFO/WARN flood. */
    val errorEvents: StateFlow<List<DebugEvent>> = _errorEvents.asStateFlow()

    private var writer: LogWriter? = null
    private var scope: CoroutineScope? = null

    /**
     * Initializes disk persistence. Called once from Application.onCreate().
     *
     * @param scope  CoroutineScope (typically SupervisorJob + Dispatchers.IO)
     * @param writer LogWriter implementation (typically CrashLogStore)
     */
    fun init(
        scope: CoroutineScope,
        writer: LogWriter,
    ) {
        this.scope = scope
        this.writer = writer
    }

    /** Visible for testing — resets writer and scope to null. */
    internal fun resetForTest() {
        writer = null
        scope = null
    }

    fun log(
        level: Level,
        tag: String,
        message: String,
    ) {
        val event =
            DebugEvent(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
            )
        _events.update { current ->
            val updated = ArrayList<DebugEvent>(minOf(current.size + 1, MAX_EVENTS))
            updated.add(event)
            val keep = current.take(MAX_EVENTS - 1)
            updated.addAll(keep)
            updated
        }
        if (level == Level.ERROR) {
            _errorEvents.update { current ->
                val updated = ArrayList<DebugEvent>(minOf(current.size + 1, MAX_ERROR_EVENTS))
                updated.add(event)
                val keep = current.take(MAX_ERROR_EVENTS - 1)
                updated.addAll(keep)
                updated
            }
            // Forward to Sentry (no-op if not initialized, i.e. when DSN is empty).
            // Runs AFTER the in-memory ring-buffer update so local persistence is
            // the source of truth and Sentry is best-effort egress. We use
            // captureMessage because DebugLog.log takes a pre-formatted message
            // string (not a Throwable). Callers that have a Throwable should call
            // Sentry.captureException directly at the call site.
            runCatching { Sentry.captureMessage(message, SentryLevel.ERROR) }
        }
        val priority =
            when (level) {
                Level.INFO -> Log.INFO
                Level.WARN -> Log.WARN
                Level.ERROR -> Log.ERROR
                Level.SUCCESS -> Log.INFO
            }
        // android.util.Log is not mocked in JVM unit tests; swallow the failure so
        // the in-memory event log still works there (tests assert on events, not logcat).
        runCatching {
            Log.println(priority, tag, message)
        }

        // Fire-and-forget disk write — never blocks the calling thread
        scope?.launch {
            runCatching {
                writer?.write(level.name, tag, message, System.currentTimeMillis())
            }
        }
    }

    fun info(
        tag: String,
        message: String,
    ) = log(Level.INFO, tag, message)

    fun warn(
        tag: String,
        message: String,
    ) = log(Level.WARN, tag, message)

    fun error(
        tag: String,
        message: String,
    ) = log(Level.ERROR, tag, message)

    fun success(
        tag: String,
        message: String,
    ) = log(Level.SUCCESS, tag, message)

    fun clear() {
        _events.update { emptyList() }
        _errorEvents.update { emptyList() }
    }

    /**
     * Plain-text dump of all events (newest first), for clipboard sharing.
     */
    fun toText(): String {
        val list = _events.value
        if (list.isEmpty()) return "(empty)"
        val sb = StringBuilder()
        for (e in list) {
            sb
                .append('[')
                .append(e.level.name)
                .append("] ")
                .append(e.tag)
                .append(": ")
                .append(e.message)
                .append('\n')
        }
        return sb.toString()
    }
}
