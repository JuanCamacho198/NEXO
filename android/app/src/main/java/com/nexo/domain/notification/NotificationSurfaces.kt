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
 * Injectable OS-notification sink (NOTIF-04). The Android adapter owns the
 * channel + `NotificationManagerCompat`; the domain layer only decides WHEN to
 * post and hands over the notification verbatim so the surface stays testable
 * on the JVM.
 */
fun interface OsNotifier {
    fun post(notification: Notification)
}

/**
 * Notification surfaces with exactly-once dispatch per surface (NOTIF-03/04).
 *
 * Routes one accepted notification to exactly the surfaces its policy
 * resolution allows: at most one toast AND at most one OS notification, each
 * only when the resolution permits it ([PolicyResolution.delivery]).
 *
 * A per-surface delivered set keyed by `"${id}:${surface}"` makes a
 * double-dispatch of the same event impossible even if [dispatch] is
 * re-entered, and a resolution with `delivery.toast == false` (respectively
 * `delivery.os == false`) never fires that surface. The tray is the caller's
 * job (the entry is already stored).
 *
 * [osNotifier] is optional so a JVM-only caller (or the toast-only slice) can
 * construct the surfaces without an Android-backed poster.
 */
class NotificationSurfaces(
    private val toastSink: ToastSink,
    private val osNotifier: OsNotifier? = null,
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
     * Fires at most one toast and at most one OS notification per notification,
     * never a surface the resolution forbids. Returns true when ANY surface was
     * emitted (so a caller can tell a delivery from a no-op).
     */
    fun dispatch(
        notification: Notification,
        resolution: PolicyResolution,
    ): Boolean {
        if (!resolution.accepted) return false
        val toasted = dispatchToast(notification, resolution)
        val posted = dispatchOs(notification, resolution)
        return toasted || posted
    }

    private fun dispatchToast(
        notification: Notification,
        resolution: PolicyResolution,
    ): Boolean {
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

    private fun dispatchOs(
        notification: Notification,
        resolution: PolicyResolution,
    ): Boolean {
        val notifier = osNotifier ?: return false
        if (!resolution.delivery.os) return false
        if (!markDelivered(notification.id, SURFACE_OS)) return false
        notifier.post(notification)
        return true
    }

    /** Test seam: forgets delivered keys so a fresh dispatch can fire. */
    fun reset() = delivered.clear()

    companion object {
        const val SURFACE_TOAST: String = "toast"
        const val SURFACE_OS: String = "os"

        fun toastTypeFor(severity: Severity): ToastType =
            when (severity) {
                Severity.SUCCESS -> ToastType.SUCCESS
                Severity.ERROR -> ToastType.ERROR
                Severity.INFO, Severity.WARNING -> ToastType.INFO
            }
    }
}
