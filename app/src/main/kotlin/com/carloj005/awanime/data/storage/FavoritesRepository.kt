package com.carloj005.awanime.data.storage

import com.carloj005.awanime.data.model.AnimeItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class FavoritesRepository(
    filesDir: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = JsonFileStore(
        file = File(filesDir, "favorites.json"),
        serializer = ListSerializer(AnimeItem.serializer()),
        defaultValue = emptyList(),
        ioDispatcher = ioDispatcher,
    )

    val favorites: StateFlow<List<AnimeItem>> = store.state

    fun isFavorite(uniqueKey: String): Boolean {
        return store.value.any { it.uniqueKey == uniqueKey }
    }

    fun toggleFavorite(anime: AnimeItem) {
        store.update { current ->
            val mutable = current.toMutableList()
            val existingIdx = mutable.indexOfFirst { it.uniqueKey == anime.uniqueKey }
            if (existingIdx >= 0) {
                mutable.removeAt(existingIdx)
            } else {
                mutable.add(0, anime)
            }
            mutable
        }
    }

    fun remove(uniqueKey: String) {
        store.update { current -> current.filterNot { it.uniqueKey == uniqueKey } }
    }

    fun mergeImport(imported: List<AnimeItem>) {
        if (imported.isEmpty()) return
        store.update { current ->
            val byKey = LinkedHashMap<String, AnimeItem>()
            for (item in current) byKey[item.uniqueKey] = item
            for (item in imported) byKey.putIfAbsent(item.uniqueKey, item)
            byKey.values.toList()
        }
    }

    suspend fun flush() = store.flush()
}
