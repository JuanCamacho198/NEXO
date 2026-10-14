package com.nexo.domain.notification

/** The in-app toast kind derived from a notification's severity. */
enum class ToastType {
    SUCCESS,
    INFO,
    ERROR,
}

/** A toast the surfaces layer wants the UI to present. */
data class ToastRequest(
    val type: ToastType,
    /** The message KEY — rendered at display time, never a resolved string. */
    val i18nKey: String,
    val i18nParams: Map<String, String>? = null,
)

/** Injectable toast sink; the Android adapter maps a request to a UI event. */
fun interface ToastSink {
    fun push(request: ToastRequest)
}

/**
 * In-app notification surfaces with exactly-once dispatch (NOTIF-03).
 *
 * Routes one accepted notification to exactly the surfaces its policy
 * resolution allows. This slice owns the toast path only; [dispatch] is shaped
 * so the OS surface (interruption `system` + app not foregrounded) slots in
 * later without rework — a second `markDelivered(id, "os")` guarded branch.
 *
 * A per-surface delivered set keyed by `"${id}:${surface}"` makes a
 * double-dispatch of the same event impossible even if [dispatch] is
 * re-entered, and a resolution with `delivery.toast == false` never fires.
 * The tray is the caller's job (the entry is already stored).
 */
class NotificationSurfaces(
    private val toastSink: ToastSink,
) {
    /** Keys of already-delivered (notification id, surface) pairs. */
    private val delivered = mutableSetOf<String>()

    private fun markDelivered(
        id: String,
        surface: String,
    ): Boolean {
        val key = "$id:$surface"
        if (!delivered.add(key)) return false
        return true
    }

    /**
     * Fires at most one toast per notification and never a surface the
     * resolution forbids. Returns true when a toast was emitted.
     */
    fun dispatch(
        notification: Notification,
        resolution: PolicyResolution,
    ): Boolean {
        if (!resolution.accepted) return false
        if (!resolution.delivery.toast) return false
        if (!markDelivered(notification.id, SURFACE_TOAST)) return false
        toastSink.push(
            ToastRequest(
                type = toastTypeFor(notification.severity),
                i18nKey = notification.i18nKey,
                i18nParams = notification.i18nParams,
            ),
        )
        return true
    }

    /** Test seam: forgets delivered keys so a fresh dispatch can fire. */
    fun reset() = delivered.clear()

    companion object {
        const val SURFACE_TOAST: String = "toast"

        fun toastTypeFor(severity: Severity): ToastType =
            when (severity) {
                Severity.SUCCESS -> ToastType.SUCCESS
                Severity.ERROR -> ToastType.ERROR
                Severity.INFO, Severity.WARNING -> ToastType.INFO
            }
    }
}
