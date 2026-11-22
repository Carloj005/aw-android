package com.carloj005.awanime.data.storage

import android.content.Context
import com.carloj005.awanime.data.model.AnimeItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class FavoritesRepository(context: Context) {

    private val file = File(context.filesDir, "favorites.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    private val _favorites = MutableStateFlow<List<AnimeItem>>(emptyList())
    val favorites: StateFlow<List<AnimeItem>> = _favorites.asStateFlow()

    init {
        _favorites.value = readFromDisk()
    }

    private fun readFromDisk(): List<AnimeItem> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AnimeItem>>(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeToDisk(list: List<AnimeItem>) = synchronized(lock) {
        val payload = json.encodeToString(list)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
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
                added = false
            } else {
                current.add(0, anime)
                added = true
            }
            writeToDisk(current)
            current.toList()
        }
        _favorites.value = updated
        return added
    }

    fun remove(uniqueKey: String) {
        val updated = synchronized(lock) {
            val next = _favorites.value.filterNot { it.uniqueKey == uniqueKey }
            writeToDisk(next)
            next
        }
        _favorites.value = updated
    }

    fun mergeImport(imported: List<AnimeItem>) {
        if (imported.isEmpty()) return
        val updated = synchronized(lock) {
            val byKey = LinkedHashMap<String, AnimeItem>()
            for (item in _favorites.value) byKey[item.uniqueKey] = item
            for (item in imported) byKey.putIfAbsent(item.uniqueKey, item)
            val list = byKey.values.toList()
            writeToDisk(list)
            list
        }
        _favorites.value = updated
    }
}
