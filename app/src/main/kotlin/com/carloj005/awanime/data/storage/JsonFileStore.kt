package com.carloj005.awanime.data.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Store generico atomico su file JSON (`.tmp` + rename) con coda serializzata
 * su dispatcher IO dedicato, così nessuna lettura/scrittura tocca mai il Main Thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val json: Json = DefaultJson,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val fileLock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun readBlocking(defaultValue: T): T = synchronized(fileLock) {
        if (!file.exists()) return defaultValue
        runCatching {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        }.getOrDefault(defaultValue)
    }

    fun writeBlocking(value: T) = synchronized(fileLock) {
        file.parentFile?.mkdirs()
        val payload = json.encodeToString(serializer, value)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun loadAsync(defaultValue: T, onLoaded: (T) -> Unit) {
        ioScope.launch {
            val loaded = readBlocking(defaultValue)
            onLoaded(loaded)
        }
    }

    fun writeAsync(value: T) {
        ioScope.launch {
            runCatching { writeBlocking(value) }
        }
    }

    companion object {
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
