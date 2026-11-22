package com.carloj005.awanime

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.storage.DownloadRepository
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.storage.SettingsRepository
import com.carloj005.awanime.data.update.AppUpdater

class AwAnimeApp : Application(), ImageLoaderFactory {

    lateinit var animeUnityClient: AnimeUnityClient
        private set

    lateinit var historyRepository: HistoryRepository
        private set

    lateinit var downloadRepository: DownloadRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var appUpdater: AppUpdater
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        animeUnityClient = AnimeUnityClient(applicationContext)
        historyRepository = HistoryRepository(applicationContext)
        downloadRepository = DownloadRepository(applicationContext, animeUnityClient)
        settingsRepository = SettingsRepository(applicationContext)
        appUpdater = AppUpdater(applicationContext)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(animeUnityClient.imageOkHttpClient)
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: AwAnimeApp
            private set
    }
}
