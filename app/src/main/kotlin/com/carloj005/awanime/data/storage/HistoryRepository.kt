package com.carloj005.awanime.data.storage

import android.content.Context
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.EpisodeProgress
import com.carloj005.awanime.data.model.WatchHistoryEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class HistoryRepository internal constructor(
    private val store: JsonFileStore<List<WatchHistoryEntry>>,
    loadSynchronously: Boolean = false,
) {
    constructor(context: Context) : this(
        store = JsonFileStore(
            file = File(context.filesDir, "history.json"),
            serializer = ListSerializer(WatchHistoryEntry.serializer()),
        ),
        loadSynchronously = false,
    )

    private val lock = Any()
    private val removedBeforeLoad = mutableSetOf<String>()
    private var clearedBeforeLoad = false
    private var initialLoadCompleted = false
    private val initialLoad = CompletableDeferred<Unit>()

    private val _history = MutableStateFlow<List<WatchHistoryEntry>>(emptyList())
    val history: StateFlow<List<WatchHistoryEntry>> = _history.asStateFlow()

    init {
        if (loadSynchronously) {
            _history.value = store.readBlocking(emptyList()).sortedByDescending { it.updatedAt }
            initialLoadCompleted = true
            initialLoad.complete(Unit)
        } else {
            store.loadAsync(emptyList()) { disk ->
                val shouldPersist = synchronized(lock) {
                    initialLoadCompleted = true
                    val filteredDisk = if (clearedBeforeLoad) {
                        emptyList()
                    } else {
                        disk.filterNot { it.uniqueKey in removedBeforeLoad }
                    }
                    val hadPendingChanges = _history.value.isNotEmpty() || clearedBeforeLoad || removedBeforeLoad.isNotEmpty()
                    val merged = mergeHistoryEntries(filteredDisk, _history.value)
                    removedBeforeLoad.clear()
                    clearedBeforeLoad = false
                    _history.value = merged
                    hadPendingChanges
                }
                if (shouldPersist) {
                    store.writeAsync(_history.value)
                }
                initialLoad.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() {
        initialLoad.await()
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
            removedBeforeLoad.remove(anime.uniqueKey)
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

            val mergedAnime = mergeRichAnimeMetadata(anime, existing?.anime)
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
            val list = current.toList()
            _history.value = list
            list
        }
        store.writeAsync(updatedList)
    }

    fun remove(uniqueKey: String) {
        val updated = synchronized(lock) {
            if (!initialLoadCompleted) removedBeforeLoad.add(uniqueKey)
            val next = _history.value.filterNot { it.uniqueKey == uniqueKey }
            _history.value = next
            next
        }
        store.writeAsync(updated)
    }

    fun clearAll() {
        synchronized(lock) {
            if (!initialLoadCompleted) clearedBeforeLoad = true
            removedBeforeLoad.clear()
            _history.value = emptyList()
        }
        store.writeAsync(emptyList())
    }

    fun mergeImport(imported: List<WatchHistoryEntry>) {
        if (imported.isEmpty()) return
        val merged = synchronized(lock) {
            for (item in imported) removedBeforeLoad.remove(item.uniqueKey)
            val list = mergeHistoryEntries(_history.value, imported)
            _history.value = list
            list
        }
        store.writeAsync(merged)
    }

    companion object {
        private fun mergeRichAnimeMetadata(current: AnimeItem, existing: AnimeItem?): AnimeItem {
            if (existing == null) return current
            return current.copy(
                posterUrl = current.posterUrl ?: existing.posterUrl,
                bannerUrl = current.bannerUrl ?: existing.bannerUrl,
                coverUrl = current.coverUrl ?: existing.coverUrl,
                plot = current.plot.ifBlank { existing.plot },
                genres = current.genres.ifEmpty { existing.genres },
                studios = current.studios.ifEmpty { existing.studios },
                anilistId = current.anilistId.takeIf { it > 0 } ?: existing.anilistId,
                malId = current.malId.takeIf { it > 0 } ?: existing.malId,
                totalEpisodes = current.totalEpisodes ?: existing.totalEpisodes,
                releasedEpisodes = maxOf(current.releasedEpisodes, existing.releasedEpisodes),
            )
        }

        internal fun mergeHistoryEntries(
            current: List<WatchHistoryEntry>,
            imported: List<WatchHistoryEntry>,
        ): List<WatchHistoryEntry> {
            val byKey = LinkedHashMap<String, WatchHistoryEntry>()
            for (entry in current) {
                byKey[entry.uniqueKey] = entry
            }
            for (incoming in imported) {
                val existing = byKey[incoming.uniqueKey]
                if (existing == null) {
                    byKey[incoming.uniqueKey] = incoming
                } else {
                    val mergedEps = mergeEpisodeProgressMaps(existing.episodesProgress, incoming.episodesProgress)
                    val primary = if (incoming.updatedAt >= existing.updatedAt) incoming else existing
                    val fallbackAnime = if (primary === incoming) existing.anime else incoming.anime
                    byKey[incoming.uniqueKey] = primary.copy(
                        anime = mergeRichAnimeMetadata(primary.anime, fallbackAnime),
                        episodesProgress = mergedEps,
                    )
                }
            }
            return byKey.values.sortedByDescending { it.updatedAt }
        }

        private fun mergeEpisodeProgressMaps(
            current: Map<String, EpisodeProgress>,
            incoming: Map<String, EpisodeProgress>,
        ): Map<String, EpisodeProgress> {
            val merged = LinkedHashMap(current)
            for ((epNum, incEp) in incoming) {
                val prevEp = merged[epNum]
                val shouldTakeIncoming = prevEp == null ||
                    incEp.updatedAt > prevEp.updatedAt ||
                    (incEp.updatedAt == prevEp.updatedAt && incEp.completed && !prevEp.completed)
                if (shouldTakeIncoming) {
                    merged[epNum] = incEp
                }
            }
            return merged
        }
    }
}
