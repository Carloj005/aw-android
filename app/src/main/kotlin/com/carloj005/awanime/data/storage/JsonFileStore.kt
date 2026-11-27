package com.carloj005.awanime.data.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thread-safe, non-blocking JSON file store.
 * - Loads initial state on [ioDispatcher] (never blocks Main/UI thread).
 * - Coalesces rapid in-memory updates via a conflated channel and writes atomically via `.tmp` rename.
 * - Preserves a `.corrupt.bak` copy if an existing non-empty JSON file fails to parse, preventing silent data loss.
 */
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
    private val json: Json = DefaultJson,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val normalizeLoaded: (T) -> Pair<T, Boolean> = { it to false },
) {
    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateLock = Any()
    private val diskMutex = Mutex()
    private val initDone = CompletableDeferred<Unit>()
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)

    private var isLoaded = false
    private val pendingTransforms = mutableListOf<(T) -> T>()

    private val _state = MutableStateFlow(defaultValue)
    val state: StateFlow<T> = _state.asStateFlow()

    init {
        scope.launch {
            val (diskValue, needsInitialWrite) = readFromDiskSafely()
            val shouldPersist = synchronized(stateLock) {
                var merged = diskValue
                for (transform in pendingTransforms) {
                    merged = transform(merged)
                }
                val hadPending = pendingTransforms.isNotEmpty()
                pendingTransforms.clear()
                isLoaded = true
                _state.value = merged
                needsInitialWrite || hadPending
            }
            if (shouldPersist) {
                writeSnapshotToDisk(_state.value)
            }
            initDone.complete(Unit)

            for (signal in writeSignal) {
                writeSnapshotToDisk(_state.value)
            }
        }
    }

    val value: T get() = _state.value

    suspend fun awaitLoaded(): T {
        initDone.await()
        return _state.value
    }

    fun update(transform: (T) -> T) {
        val shouldSignal = synchronized(stateLock) {
            if (!isLoaded) {
                pendingTransforms.add(transform)
                _state.value = transform(_state.value)
                false
            } else {
                val updated = transform(_state.value)
                _state.value = updated
                true
            }
        }
        if (shouldSignal) {
            writeSignal.trySend(Unit)
        }
    }

    suspend fun flush() {
        initDone.await()
        withContext(ioDispatcher) {
            writeSnapshotToDisk(_state.value)
        }
    }

    private suspend fun readFromDiskSafely(): Pair<T, Boolean> = diskMutex.withLock {
        if (!file.exists()) return@withLock defaultValue to false
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        if (raw.isNullOrBlank()) return@withLock defaultValue to false

        return@withLock try {
            val parsed = json.decodeFromString(serializer, raw)
            normalizeLoaded(parsed)
        } catch (_: Exception) {
            runCatching {
                val backup = File(file.parentFile, "${file.name}.corrupt.bak")
                file.copyTo(backup, overwrite = true)
            }
            defaultValue to false
        }
    }

    private suspend fun writeSnapshotToDisk(snapshot: T) = diskMutex.withLock {
        runCatching {
            file.parentFile?.mkdirs()
            val payload = json.encodeToString(serializer, snapshot)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}
