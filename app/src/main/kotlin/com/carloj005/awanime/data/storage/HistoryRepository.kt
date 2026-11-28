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

    suspend fun awaitLoaded(): List<WatchHistoryEntry> = store.awaitLoaded()

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
        isAtLatestEpisode: Boolean = false,
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
                isAtLatestEpisode = completed && isAtLatestEpisode,
                updatedAt = now,
                episodesProgress = mergedEps,
            )

            mutable.add(0, entry)
            mutable
        }
    }

    /**
     * Synchronizes newly observed episode availability with an existing history entry so that:
     * - Legacy completed entries at the last episode are accurately marked caught-up.
     * - Ongoing anime automatically reappear in "Continua a guardare" as soon as a newer episode drops.
     */
    fun syncKnownEpisodeAvailability(
        uniqueKey: String,
        availableEpisodes: Int,
        lastEpisodeNum: String? = null,
    ) {
        if (availableEpisodes <= 0 && lastEpisodeNum.isNullOrBlank()) return
        val existing = getEntry(uniqueKey) ?: return

        val curEpInt = existing.currentEpisodeNum.trim().split(".", "-").firstOrNull()?.toIntOrNull()
        val lastEpInt = lastEpisodeNum?.trim()?.split(".", "-")?.firstOrNull()?.toIntOrNull()
        val maxKnown = maxOf(availableEpisodes, lastEpInt ?: 0, existing.anime.releasedEpisodes)

        val hasNewerEpisode = curEpInt != null && maxKnown > curEpInt
        val isAtFinalEpisode = existing.completed && (
            (!lastEpisodeNum.isNullOrBlank() && existing.currentEpisodeNum.trim() == lastEpisodeNum.trim()) ||
                (curEpInt != null && maxKnown > 0 && curEpInt >= maxKnown)
            )
        val nextAtLatest = when {
            !existing.completed -> false
            hasNewerEpisode -> false
            isAtFinalEpisode -> true
            else -> existing.isAtLatestEpisode
        }

        if (maxKnown == existing.anime.releasedEpisodes && nextAtLatest == existing.isAtLatestEpisode) {
            return
        }

        store.update { list ->
            list.map { entry ->
                if (entry.uniqueKey != uniqueKey) {
                    entry
                } else {
                    entry.copy(
                        anime = entry.anime.copy(releasedEpisodes = maxOf(entry.anime.releasedEpisodes, maxKnown)),
                        isAtLatestEpisode = nextAtLatest,
                    )
                }
            }
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
                    val prevCompleted = prev.episodesProgress.values.count { it.completed }
                    val incCompleted = inc.episodesProgress.values.count { it.completed }
                    val primary = when {
                        inc.updatedAt > prev.updatedAt -> inc
                        prev.updatedAt > inc.updatedAt -> {
                            // Heal partial/truncated imports (e.g. 47/224) when a fuller caught-up import arrives
                            if (incCompleted > prevCompleted && inc.isAtLatestEpisode) inc else prev
                        }
                        incCompleted >= prevCompleted -> inc
                        else -> prev
                    }
                    val fallbackAnime = if (primary === inc) prev.anime else inc.anime
                    byKey[inc.uniqueKey] = primary.copy(
                        anime = mergeRichAnimeMetadata(primary.anime, fallbackAnime),
                        updatedAt = maxOf(prev.updatedAt, inc.updatedAt),
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
            posterUrl = incoming.posterUrl?.takeIf { it.isNotBlank() } ?: previous.posterUrl?.takeIf { it.isNotBlank() },
            bannerUrl = incoming.bannerUrl?.takeIf { it.isNotBlank() } ?: previous.bannerUrl?.takeIf { it.isNotBlank() },
            coverUrl = incoming.coverUrl?.takeIf { it.isNotBlank() } ?: previous.coverUrl?.takeIf { it.isNotBlank() },
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
