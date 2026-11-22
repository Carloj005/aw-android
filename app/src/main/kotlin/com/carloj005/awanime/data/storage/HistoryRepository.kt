package com.carloj005.awanime.data.storage

import android.content.Context
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.EpisodeProgress
import com.carloj005.awanime.data.model.WatchHistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class HistoryRepository(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    private val _history = MutableStateFlow<List<WatchHistoryEntry>>(emptyList())
    val history: StateFlow<List<WatchHistoryEntry>> = _history.asStateFlow()

    init {
        _history.value = readFromDisk()
    }

    private fun readFromDisk(): List<WatchHistoryEntry> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<WatchHistoryEntry>>(file.readText(Charsets.UTF_8))
                .sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    private fun writeToDisk(list: List<WatchHistoryEntry>) = synchronized(lock) {
        val payload = json.encodeToString(list)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun getEntry(uniqueKey: String): WatchHistoryEntry? {
        return _history.value.firstOrNull { it.uniqueKey == uniqueKey }
    }

    fun getEpisodeProgress(animeUniqueKey: String, episodeNum: String): EpisodeProgress? {
        return getEntry(animeUniqueKey)?.episodesProgress?.get(episodeNum)
    }

    fun updateProgress(
        anime: AnimeItem,
        episodeNum: String,
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        val completed = durationMs > 0L && (positionMs * 100L / durationMs) >= 90L
        val savedPosition = if (completed) 0L else positionMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()

        val updatedList = synchronized(lock) {
            val current = _history.value.toMutableList()
            val existingIdx = current.indexOfFirst { it.uniqueKey == anime.uniqueKey }
            val existing = if (existingIdx >= 0) current.removeAt(existingIdx) else null

            val mergedEps = (existing?.episodesProgress ?: emptyMap()).toMutableMap()
            mergedEps[episodeNum] = EpisodeProgress(
                episodeNum = episodeNum,
                videoId = videoId,
                positionMs = savedPosition,
                durationMs = durationMs.coerceAtLeast(0L),
                completed = completed,
                updatedAt = now,
            )

            // Conserva metadati ricchi se presenti
            val mergedAnime = anime.copy(
                posterUrl = anime.posterUrl ?: existing?.anime?.posterUrl,
                bannerUrl = anime.bannerUrl ?: existing?.anime?.bannerUrl,
                coverUrl = anime.coverUrl ?: existing?.anime?.coverUrl,
                plot = anime.plot.ifBlank { existing?.anime?.plot.orEmpty() },
                genres = anime.genres.ifEmpty { existing?.anime?.genres.orEmpty() },
                studios = anime.studios.ifEmpty { existing?.anime?.studios.orEmpty() },
                anilistId = anime.anilistId.takeIf { it > 0 } ?: (existing?.anime?.anilistId ?: 0),
                malId = anime.malId.takeIf { it > 0 } ?: (existing?.anime?.malId ?: 0),
                totalEpisodes = anime.totalEpisodes ?: existing?.anime?.totalEpisodes,
                releasedEpisodes = maxOf(anime.releasedEpisodes, existing?.anime?.releasedEpisodes ?: 0),
            )

            val entry = WatchHistoryEntry(
                anime = mergedAnime,
                currentEpisodeNum = episodeNum,
                currentVideoId = videoId,
                progressMs = savedPosition,
                durationMs = durationMs.coerceAtLeast(0L),
                completed = completed,
                updatedAt = now,
                episodesProgress = mergedEps,
            )

            current.add(0, entry)
            writeToDisk(current)
            current.toList()
        }
        _history.value = updatedList
    }

    fun remove(uniqueKey: String) {
        val updated = synchronized(lock) {
            val next = _history.value.filterNot { it.uniqueKey == uniqueKey }
            writeToDisk(next)
            next
        }
        _history.value = updated
    }

    fun clearAll() {
        synchronized(lock) {
            writeToDisk(emptyList())
        }
        _history.value = emptyList()
    }

    fun mergeImport(imported: List<WatchHistoryEntry>) {
        if (imported.isEmpty()) return
        val merged = synchronized(lock) {
            val byKey = LinkedHashMap<String, WatchHistoryEntry>()
            for (e in _history.value) byKey[e.uniqueKey] = e
            for (inc in imported) {
                val prev = byKey[inc.uniqueKey]
                if (prev == null || inc.updatedAt >= prev.updatedAt) {
                    val mergedEps = (prev?.episodesProgress ?: emptyMap()) + inc.episodesProgress
                    byKey[inc.uniqueKey] = inc.copy(episodesProgress = mergedEps)
                }
            }
            val list = byKey.values.sortedByDescending { it.updatedAt }
            writeToDisk(list)
            list
        }
        _history.value = merged
    }
}
