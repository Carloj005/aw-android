package com.carloj005.awanime.data.storage

import android.content.Context
import com.carloj005.awanime.data.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class SettingsRepository(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()

    private val _settings = MutableStateFlow(readFromDisk())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun readFromDisk(): AppSettings = synchronized(lock) {
        if (!file.exists()) return AppSettings()
        return runCatching {
            json.decodeFromString<AppSettings>(file.readText(Charsets.UTF_8))
        }.getOrDefault(AppSettings())
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = synchronized(lock) {
            val updated = transform(_settings.value)
            val payload = json.encodeToString(updated)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
            updated
        }
        _settings.value = next
    }
}
