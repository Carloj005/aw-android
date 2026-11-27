package com.carloj005.awanime.data.storage

import android.content.Context
import com.carloj005.awanime.data.model.AnimeItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class FavoritesRepository internal constructor(
    private val store: JsonFileStore<List<AnimeItem>>,
    loadSynchronously: Boolean = false,
) {
    constructor(context: Context) : this(
        store = JsonFileStore(
            file = File(context.filesDir, "favorites.json"),
            serializer = ListSerializer(AnimeItem.serializer()),
        ),
        loadSynchronously = false,
    )

    private val lock = Any()
    private val removedBeforeLoad = mutableSetOf<String>()
    private var initialLoadCompleted = false
    private val initialLoad = CompletableDeferred<Unit>()

    private val _favorites = MutableStateFlow<List<AnimeItem>>(emptyList())
    val favorites: StateFlow<List<AnimeItem>> = _favorites.asStateFlow()

    init {
        if (loadSynchronously) {
            _favorites.value = store.readBlocking(emptyList())
            initialLoadCompleted = true
            initialLoad.complete(Unit)
        } else {
            store.loadAsync(emptyList()) { disk ->
                val merged = synchronized(lock) {
                    initialLoadCompleted = true
                    val byKey = LinkedHashMap<String, AnimeItem>()
                    for (item in _favorites.value) byKey[item.uniqueKey] = item
                    for (item in disk) {
                        if (item.uniqueKey !in removedBeforeLoad) {
                            byKey.putIfAbsent(item.uniqueKey, item)
                        }
                    }
                    removedBeforeLoad.clear()
                    val list = byKey.values.toList()
                    _favorites.value = list
                    list
                }
                if (merged.size != disk.size) {
                    store.writeAsync(merged)
                }
                initialLoad.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() {
        initialLoad.await()
    }

    fun isFavorite(uniqueKey: String): Boolean {
        return _favorites.value.any { it.uniqueKey == uniqueKey }
    }

    fun toggleFavorite(anime: AnimeItem): Boolean {
        var added = false
        val updated = synchronized(lock) {
            val current = _favorites.value.toMutableList()
            val idx = current.indexOfFirst { it.uniqueKey == anime.uniqueKey }
            if (idx >= 0) {
                current.removeAt(idx)
                if (!initialLoadCompleted) removedBeforeLoad.add(anime.uniqueKey)
                added = false
            } else {
                removedBeforeLoad.remove(anime.uniqueKey)
                current.add(0, anime)
                added = true
            }
            val list = current.toList()
            _favorites.value = list
            list
        }
        store.writeAsync(updated)
        return added
    }

    fun remove(uniqueKey: String) {
        val updated = synchronized(lock) {
            if (!initialLoadCompleted) removedBeforeLoad.add(uniqueKey)
            val next = _favorites.value.filterNot { it.uniqueKey == uniqueKey }
            _favorites.value = next
            next
        }
        store.writeAsync(updated)
    }

    fun mergeImport(imported: List<AnimeItem>) {
        if (imported.isEmpty()) return
        val updated = synchronized(lock) {
            val byKey = LinkedHashMap<String, AnimeItem>()
            for (item in _favorites.value) byKey[item.uniqueKey] = item
            for (item in imported) {
                removedBeforeLoad.remove(item.uniqueKey)
                byKey.putIfAbsent(item.uniqueKey, item)
            }
            val list = byKey.values.toList()
            _favorites.value = list
            list
        }
        store.writeAsync(updated)
    }
}
