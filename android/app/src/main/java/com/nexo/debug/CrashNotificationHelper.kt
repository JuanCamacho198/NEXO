package com.nexo.debug

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nexo.NexoApplication
import com.nexo.R
import org.json.JSONObject

/**
 * Posts a debug-only notification when the app crashed on a previous
 * launch and was relaunched.
 *
 * - Only active in [com.nexo.BuildConfig.DEBUG] builds.
 * - Reads the last crash from [NexoApplication.PREFS_NAME] /
 *   [NexoApplication.KEY_LAST_CRASH].
 * - Clears the persisted crash after posting so each crash only
 *   triggers one notification.
 */
object CrashNotificationHelper {
    const val CHANNEL_ID = "nexo_debug_crashes"
    const val NOTIFICATION_ID = 7301
    const val EXTRA_CRASH_JSON = "crash_json"

    /**
     * Creates the notification channel on API 26+ (no-op on lower APIs).
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.debug_crash_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.debug_crash_channel_description)
                }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Reads any pending crash from SharedPreferences, posts a notification
     * pointing to [CrashDetailActivity], and clears the entry.
     *
     * Returns immediately in non-debug builds.
     */
    fun showCrashNotificationIfAny(context: Context) {
        if (!com.nexo.BuildConfig.DEBUG) return
        val prefs =
            context.getSharedPreferences(
                NexoApplication.PREFS_NAME,
                Context.MODE_PRIVATE,
            )
        val raw = prefs.getString(NexoApplication.KEY_LAST_CRASH, null) ?: return
        // Clear immediately so the notification only shows once.
        prefs.edit().remove(NexoApplication.KEY_LAST_CRASH).apply()

        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return

        val detailIntent =
            Intent(context, CrashDetailActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_CRASH_JSON, parsed.toString())
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                detailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.debug_crash_notification_title))
                .setContentText(context.getString(R.string.debug_crash_notification_body))
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(context.getString(R.string.debug_crash_notification_body)),
                ).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
