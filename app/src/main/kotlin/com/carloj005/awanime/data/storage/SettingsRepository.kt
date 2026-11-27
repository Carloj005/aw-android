package com.carloj005.awanime.data.storage

import com.carloj005.awanime.data.model.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class SettingsRepository(
    filesDir: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = JsonFileStore(
        file = File(filesDir, "settings.json"),
        serializer = AppSettings.serializer(),
        defaultValue = AppSettings(),
        ioDispatcher = ioDispatcher,
    )

    val settings: StateFlow<AppSettings> = store.state

    suspend fun awaitLoaded(): AppSettings = store.awaitLoaded()

    fun update(transform: (AppSettings) -> AppSettings) {
        store.update(transform)
    }

    fun replace(newSettings: AppSettings) {
        store.update { newSettings }
    }

    suspend fun flush() = store.flush()
}
