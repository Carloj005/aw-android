package com.carloj005.awanime.data.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.util.runCatchingCancellable

/**
 * Periodic WorkManager worker that checks favorited anime for newly released episodes
 * and posts local Android notifications when an update is detected.
 */
class FavoriteEpisodesCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? AwAnimeApp ?: return Result.failure()
        return runCatchingCancellable {
            app.container.favoriteNotificationManager.checkFavoritesForNewEpisodes(
                respectSettingToggle = true,
                postNotifications = true,
            )
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
