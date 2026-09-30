package com.nexo.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class UpdateDownloader(
    private val appContext: Context,
) {
    fun fileNameFor(
        version: String,
        assetUrl: String,
    ): String {
        val lastSegment = assetUrl.substringAfterLast('/').substringBefore('?')
        return lastSegment.ifBlank { "nextpage-update-v$version.apk" }
    }

    fun enqueue(
        version: String,
        assetUrl: String,
    ): Long {
        val manager = appContext.getSystemService(DownloadManager::class.java) ?: return FAILED_ID
        return try {
            val request =
                DownloadManager
                    .Request(Uri.parse(assetUrl))
                    .setTitle("NEXO v$version")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(
                        appContext,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileNameFor(version, assetUrl),
                    )
            manager.enqueue(request)
        } catch (err: Exception) {
            FAILED_ID
        }
    }

    fun awaitCompletion(
        downloadId: Long,
        fileName: String,
        onDone: (installed: Boolean) -> Unit,
    ) {
        if (downloadId == FAILED_ID) {
            onDone(false)
            return
        }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, FAILED_ID) != downloadId) return
                    runCatching { appContext.unregisterReceiver(this) }
                    onDone(installDownloadedApk(fileName))
                }
            }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun installDownloadedApk(fileName: String): Boolean {
        val downloadsDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        val apk = File(downloadsDir, fileName)
        if (!apk.exists()) return false
        return try {
            val uri =
                FileProvider.getUriForFile(appContext, appContext.packageName + FILE_PROVIDER_SUFFIX, apk)
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            appContext.startActivity(intent)
            true
        } catch (err: Exception) {
            false
        }
    }

    private companion object {
        const val FAILED_ID = -1L
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
