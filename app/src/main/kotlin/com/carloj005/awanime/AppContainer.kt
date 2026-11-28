package com.carloj005.awanime

import android.content.Context
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.network.HttpClients
import com.carloj005.awanime.data.network.NetworkMonitor
import com.carloj005.awanime.data.notifications.FavoriteEpisodeNotificationManager
import com.carloj005.awanime.data.storage.DownloadRepository
import com.carloj005.awanime.data.storage.FavoritesRepository
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.storage.SettingsRepository
import com.carloj005.awanime.data.storage.TrackedNotificationsRepository
import com.carloj005.awanime.data.update.AppUpdater
import java.io.File

/**
 * Lightweight dependency injection container replacing global static singleton access.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val cacheDir: File = appContext.cacheDir
    val httpClients = HttpClients()
    val networkMonitor = NetworkMonitor(appContext)
    val animeUnityClient = AnimeUnityClient(
        context = appContext,
        httpClients = httpClients,
        networkMonitor = networkMonitor,
    )

    val settingsRepository = SettingsRepository(appContext.filesDir)
    val historyRepository = HistoryRepository(appContext.filesDir)
    val favoritesRepository = FavoritesRepository(appContext.filesDir)
    val trackedNotificationsRepository = TrackedNotificationsRepository(appContext.filesDir)
    val downloadRepository = DownloadRepository(
        context = appContext,
        client = animeUnityClient,
        downloadHttpClient = httpClients.downloadClient,
        settingsRepo = settingsRepository,
        networkMonitor = networkMonitor,
    )
    val favoriteNotificationManager = FavoriteEpisodeNotificationManager(
        context = appContext,
        client = animeUnityClient,
        favoritesRepo = favoritesRepository,
        historyRepo = historyRepository,
        trackedRepo = trackedNotificationsRepository,
        settingsRepo = settingsRepository,
        filesDir = appContext.filesDir,
    )
    val appUpdater = AppUpdater(
        context = appContext,
        sharedDispatcher = httpClients.sharedDispatcher,
    )
}
