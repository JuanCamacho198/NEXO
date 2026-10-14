package com.nexo.debug

import android.util.Log
import com.nexo.BuildConfig
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
    enum class Level { DEBUG, INFO, WARN, ERROR, SUCCESS }

    data class DebugEvent(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    private const val MAX_EVENTS = 500
    private const val MAX_ERROR_EVENTS = 200

    /**
     * Severity threshold for gating. Events strictly below it are dropped
     * before any side effect (ring, Logcat, Sentry, disk).
     *
     * Release builds must never emit DEBUG: the default is DEBUG only when
     * [BuildConfig.DEBUG] is set, otherwise INFO. Exposed as [minLevel] /
     * [setMinLevel] for tests and runtime tuning.
     */
    @Volatile
    var minLevel: Level = if (BuildConfig.DEBUG) Level.DEBUG else Level.INFO
        private set

    fun setMinLevel(level: Level) {
        minLevel = level
    }

    /**
     * Gate severity, mapping [Level.SUCCESS] onto INFO (it is a positive
     * signal, not a warning). Lower ordinal == less severe.
     */
    private fun gateRank(level: Level): Int = if (level == Level.SUCCESS) Level.INFO.ordinal else level.ordinal

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
        minLevel = if (BuildConfig.DEBUG) Level.DEBUG else Level.INFO
    }

    fun log(
        level: Level,
        tag: String,
        message: String,
    ) {
        // Level gate FIRST: a dropped event must have zero side effect.
        if (gateRank(level) < gateRank(minLevel)) return

        // Redact ONCE, before the message reaches any sink (ring, Logcat,
        // Sentry, disk). Stored events are therefore already redacted and
        // toText() must NOT re-run the boundary.
        val safeMessage = LogRedaction.redactString(message)
        val event =
            DebugEvent(
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = safeMessage,
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
            runCatching { Sentry.captureMessage(safeMessage, SentryLevel.ERROR) }
        }
        val priority =
            when (level) {
                Level.DEBUG -> Log.DEBUG
                Level.INFO -> Log.INFO
                Level.WARN -> Log.WARN
                Level.ERROR -> Log.ERROR
                Level.SUCCESS -> Log.INFO
            }
        // android.util.Log is not mocked in JVM unit tests; swallow the failure so
        // the in-memory event log still works there (tests assert on events, not logcat).
        runCatching {
            Log.println(priority, tag, safeMessage)
        }

        // Fire-and-forget disk write — never blocks the calling thread
        scope?.launch {
            runCatching {
                writer?.write(level.name, tag, safeMessage, System.currentTimeMillis())
            }
        }
    }

    fun debug(
        tag: String,
        message: String,
    ) = log(Level.DEBUG, tag, message)

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
