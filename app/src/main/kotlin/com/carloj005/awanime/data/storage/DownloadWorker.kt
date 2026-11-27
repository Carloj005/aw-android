package com.carloj005.awanime.data.storage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.R
import com.carloj005.awanime.data.model.DownloadEntry

/**
 * WorkManager worker for resilient MP4 episode downloads in background.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_DOWNLOAD_ID = "download_key"
        private const val CHANNEL_ID = "aw_episode_downloads"
    }

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_DOWNLOAD_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val app = applicationContext as? AwAnimeApp ?: return Result.failure()
        val repo = app.container.downloadRepository

        ensureNotificationChannel()
        repo.getEntryByKey(key)?.let { tryPromoteForeground(it) }

        val succeeded = repo.executeScheduledDownload(key) { progressEntry ->
            tryPromoteForeground(progressEntry)
        }
        return if (succeeded) Result.success() else Result.failure()
    }

    private suspend fun tryPromoteForeground(entry: DownloadEntry) {
        runCatching { setForeground(buildForegroundInfo(entry)) }
    }

    private fun buildForegroundInfo(entry: DownloadEntry): ForegroundInfo {
        val percent = (entry.progressFraction * 100f).toInt().coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(entry.animeTitle)
            .setContentText("Download Ep. ${entry.episodeNum} ($percent%)")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, entry.totalBytes <= 0L)
            .build()

        val notificationId = (entry.key.hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Download Episodi",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Avanzamento download episodi offline"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}
