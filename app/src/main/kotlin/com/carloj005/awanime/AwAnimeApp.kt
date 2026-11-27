package com.carloj005.awanime

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class AwAnimeApp : Application(), ImageLoaderFactory {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Trigger lazy initialization eagerly on normal app startup while keeping
        // cold-start WorkManager / ContentProvider access crash-safe.
        container
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(container.httpClients.imageClient)
            .crossfade(true)
            .build()
    }
}
