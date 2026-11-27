package com.carloj005.awanime.data.storage

import android.content.Context
import com.carloj005.awanime.data.model.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class SettingsRepository internal constructor(
    private val store: JsonFileStore<AppSettings>,
    loadSynchronously: Boolean = false,
) {
    constructor(context: Context) : this(
        store = JsonFileStore(File(context.filesDir, "settings.json"), AppSettings.serializer()),
        loadSynchronously = false,
    )

    private val lock = Any()
    private var hasLocalMutation = false
    private val initialLoad = CompletableDeferred<Unit>()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        if (loadSynchronously) {
            _settings.value = store.readBlocking(AppSettings())
            initialLoad.complete(Unit)
        } else {
            store.loadAsync(AppSettings()) { disk ->
                synchronized(lock) {
                    if (!hasLocalMutation) {
                        _settings.value = disk
                    }
                }
                initialLoad.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = synchronized(lock) {
            hasLocalMutation = true
            val updated = transform(_settings.value)
            _settings.value = updated
            updated
        }
        store.writeAsync(next)
    }

    fun replace(newSettings: AppSettings) {
        update { newSettings }
    }
}
