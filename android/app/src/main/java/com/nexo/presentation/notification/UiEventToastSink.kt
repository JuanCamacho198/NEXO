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
 * text of the request's i18n key. The key is resolved through [resolveKey] so
 * the domain layer keeps storing keys, never resolved strings; re-rendering a
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
        /** Key -> string resource. Unknown keys degrade to [R.string.notifications_empty]. */
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
    }
}
