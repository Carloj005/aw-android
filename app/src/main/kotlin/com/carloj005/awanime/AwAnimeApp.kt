package com.carloj005.awanime

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory

class AwAnimeApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient(container.httpClients.imageClient)
            .crossfade(true)
            .build()
    }
}
