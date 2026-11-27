package com.carloj005.awanime.data.storage

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.EpisodeProgress
import com.carloj005.awanime.data.model.HISTORY_COMPLETION_PERCENT
import com.carloj005.awanime.data.model.WatchHistoryEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class HistoryRepository(
    filesDir: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = JsonFileStore(
        file = File(filesDir, "history.json"),
        serializer = ListSerializer(WatchHistoryEntry.serializer()),
        defaultValue = emptyList(),
        ioDispatcher = ioDispatcher,
        normalizeLoaded = { list -> list.sortedByDescending { it.updatedAt } to false },
    )

    val history: StateFlow<List<WatchHistoryEntry>> = store.state

    fun getEntry(uniqueKey: String): WatchHistoryEntry? {
        return store.value.firstOrNull { it.uniqueKey == uniqueKey }
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
        val completed = durationMs > 0L && (positionMs * 100L / durationMs) >= HISTORY_COMPLETION_PERCENT
        val savedPosition = if (completed) 0L else positionMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis()

        store.update { currentList ->
            val mutable = currentList.toMutableList()
            val existingIdx = mutable.indexOfFirst { it.uniqueKey == anime.uniqueKey }
            val existing = if (existingIdx >= 0) mutable.removeAt(existingIdx) else null

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

            mutable.add(0, entry)
            mutable
        }
    }

    fun remove(uniqueKey: String) {
        store.update { current -> current.filterNot { it.uniqueKey == uniqueKey } }
    }

    fun clearAll() {
        store.update { emptyList() }
    }

    fun mergeImport(imported: List<WatchHistoryEntry>) {
        if (imported.isEmpty()) return
        store.update { current ->
            val byKey = LinkedHashMap<String, WatchHistoryEntry>()
            for (entry in current) byKey[entry.uniqueKey] = entry
            for (inc in imported) {
                val prev = byKey[inc.uniqueKey]
                if (prev == null) {
                    byKey[inc.uniqueKey] = inc
                } else {
                    val mergedEps = mergeEpisodeProgressMaps(prev.episodesProgress, inc.episodesProgress)
                    val primary = if (inc.updatedAt >= prev.updatedAt) inc else prev
                    val fallbackAnime = if (primary === inc) prev.anime else inc.anime
                    byKey[inc.uniqueKey] = primary.copy(
                        anime = mergeRichAnimeMetadata(primary.anime, fallbackAnime),
                        episodesProgress = mergedEps,
                    )
                }
            }
            byKey.values.sortedByDescending { it.updatedAt }
        }
    }

    suspend fun flush() = store.flush()

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

    private fun mergeRichAnimeMetadata(incoming: AnimeItem, previous: AnimeItem?): AnimeItem {
        if (previous == null) return incoming
        return incoming.copy(
            posterUrl = incoming.posterUrl ?: previous.posterUrl,
            bannerUrl = incoming.bannerUrl ?: previous.bannerUrl,
            coverUrl = incoming.coverUrl ?: previous.coverUrl,
            plot = incoming.plot.ifBlank { previous.plot },
            genres = incoming.genres.ifEmpty { previous.genres },
            studios = incoming.studios.ifEmpty { previous.studios },
            anilistId = incoming.anilistId.takeIf { it > 0 } ?: previous.anilistId,
            malId = incoming.malId.takeIf { it > 0 } ?: previous.malId,
            totalEpisodes = incoming.totalEpisodes ?: previous.totalEpisodes,
            releasedEpisodes = maxOf(incoming.releasedEpisodes, previous.releasedEpisodes),
        )
    }
}
