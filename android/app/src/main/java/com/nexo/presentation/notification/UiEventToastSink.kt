package com.nexo.presentation.notification

import androidx.annotation.StringRes
import com.nexo.R
import com.nexo.domain.notification.ToastRequest
import com.nexo.domain.notification.ToastSink
import com.nexo.domain.notification.ToastType
import com.nexo.presentation.UiEvent

/**
 * Android glue for the pure [com.nexo.domain.notification.NotificationSurfaces].
 *
 * Maps a [ToastRequest] to a [UiEvent.ShowToast] whose message is the localized
 * text of the request's i18n key. The key is resolved through [resolveMessage]
 * so the domain layer keeps storing keys, never resolved strings; re-rendering a
 * history row (or the toast) after a language switch uses the same lookup.
 *
 * Toast visual type is carried in the event message prefix-free — the app's
 * toast primitives are text-only, so [ToastType] is currently informational
 * only and a later surface can consume it without a domain change.
 */
class UiEventToastSink(
    private val emit: (UiEvent) -> Unit,
    private val resolveMessage: (ToastRequest) -> String,
) : ToastSink {
    override fun push(request: ToastRequest) {
        emit(UiEvent.ShowToast(resolveMessage(request)))
    }

    companion object {
        /**
         * Key -> string resource. Shared with [AndroidOsNotifier] so the toast
         * and the OS banner render the same message. Unknown keys degrade to
         * [R.string.notifications_empty] rather than crashing a delivery.
         */
        @StringRes
        fun stringResFor(i18nKey: String): Int =
            when (i18nKey) {
                "notifications.event.import.success" -> R.string.notifications_event_import_success
                "notifications.event.import.error" -> R.string.notifications_event_import_error
                "notifications.event.sync.success" -> R.string.notifications_event_sync_success
                "notifications.event.sync.error" -> R.string.notifications_event_sync_error
                "notifications.event.addons.success" -> R.string.notifications_event_addons_success
                "notifications.event.addons.error" -> R.string.notifications_event_addons_error
                "notifications.event.update.info" -> R.string.notifications_event_update_info
                "notifications.event.library.success" -> R.string.notifications_event_library_success
                "notifications.event.streak.success" -> R.string.notifications_event_streak_success
                "notifications.event.streak.warning" -> R.string.notifications_event_streak_warning
                "notifications.event.streak.error" -> R.string.notifications_event_streak_error
                "notifications.event.goal.success" -> R.string.notifications_event_goal_success
                "notifications.event.goal.info" -> R.string.notifications_event_goal_info
                "notifications.event.goal.warning" -> R.string.notifications_event_goal_warning
                "notifications.event.goal.error" -> R.string.notifications_event_goal_error
                else -> R.string.notifications_empty
            }

        /**
         * Resolves the key to its resource and formats any [params] using the
         * given [context]. Params are applied positionally in the order their
         * key names sort (so a caller supplies `p1`, `p2`, … and the string
         * declares `%1$s`, `%2$s`, …). A string with no placeholders ignores a
         * supplied param map, so a key can gain or lose params without breaking
         * an older stored entry; a formatting failure degrades to the raw text.
         */
        fun format(
            context: android.content.Context,
            i18nKey: String,
            params: Map<String, String>?,
        ): String {
            val res = stringResFor(i18nKey)
            if (params.isNullOrEmpty()) return context.getString(res)
            val ordered =
                params.entries
                    .sortedBy { (key, _) -> key.replace(Regex("[^0-9]"), "").toIntOrNull() ?: Int.MAX_VALUE }
                    .map { it.value }
                    .toTypedArray()
            return runCatching { context.getString(res, *ordered) }.getOrDefault(context.getString(res))
        }
    }
}
