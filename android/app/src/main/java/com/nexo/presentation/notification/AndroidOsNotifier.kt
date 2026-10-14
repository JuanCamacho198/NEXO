package com.nexo.presentation.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nexo.MainActivity
import com.nexo.R
import com.nexo.domain.notification.Notification as NexoNotification
import com.nexo.domain.notification.OsNotifier
import com.nexo.domain.notification.Severity

/**
 * Android adapter for the OS notification surface (U7).
 *
 * Mirrors [com.nexo.debug.CrashNotificationHelper]'s structure — channel
 * creation, `NotificationCompat.Builder`, a tap [PendingIntent], and a
 * `runCatching`-guarded `notify` — but on its OWN channel
 * ([CHANNEL_ID] = `nexo_notifications`, normal importance): the debug crash
 * channel is high-importance and debug-only, and reusing it would make healthy
 * notification delivery inherit crash semantics (and stop working in release
 * where the debug channel is never created).
 *
 * The notification id is derived from the domain id so a re-post of the same
 * event replaces rather than stacks, matching the exactly-once domain guard.
 */
class AndroidOsNotifier(
    private val context: Context,
) : OsNotifier {
    override fun post(notification: NexoNotification) {
        ensureChannel()
        val intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                notification.id.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val text = UiEventToastSink.format(context, notification.i18nKey, notification.i18nParams)
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(context.getString(R.string.notifications_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(priorityFor(notification.severity))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

        // Explicit permission gate: on API 33+ the OS surface needs
        // POST_NOTIFICATIONS; without a check lint fails and, more importantly,
        // a denied permission must be a silent no-op (the tray still records).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(notification.id.hashCode(), builder.build())
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notifications_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notifications_channel_description)
            }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "nexo_notifications"

        private fun priorityFor(severity: Severity): Int =
            when (severity) {
                Severity.ERROR -> NotificationCompat.PRIORITY_DEFAULT
                Severity.WARNING -> NotificationCompat.PRIORITY_DEFAULT
                Severity.SUCCESS, Severity.INFO -> NotificationCompat.PRIORITY_LOW
            }
    }
}
