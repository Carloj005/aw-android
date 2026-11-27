package com.carloj005.awanime.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

const val HISTORY_COMPLETION_PERCENT = 90L
const val SKIP_END_MARGIN_MS = 800L

@Serializable
enum class LatestFilter(val label: String) {
    ALL("Tutti"),
    SUB("SUB"),
    DUB("ITA"),
    TRENDING("Tendenze"),
}

@Serializable
enum class SearchFilter(val label: String) {
    ALL("Tutti"),
    SUB("SUB"),
    DUB("ITA"),
    ONGOING("In corso"),
    COMPLETED("Finiti"),
}

enum class AnimeStatus(val label: String) {
    ONGOING("In corso"),
    COMPLETED("Finito"),
    UPCOMING("Non rilasciato"),
    UNKNOWN("Sconosciuto");

    companion object {
        fun fromRaw(raw: String?): AnimeStatus = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "ongoing", "in corso" -> ONGOING
            "completed", "terminato", "finito" -> COMPLETED
            "upcoming", "not_yet_released", "non rilasciato" -> UPCOMING
            else -> UNKNOWN
        }
    }
}

@Serializable
enum class ThemeMode(val label: String) {
    SYSTEM("Sistema"),
    DARK("Scuro"),
    LIGHT("Chiaro"),
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColors: Boolean = true,
    val autoSkipIntro: Boolean = false,
    val autoSkipOutro: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    val wifiOnlyDownloads: Boolean = false,
    val autoPipEnabled: Boolean = true,
    val recentQueries: List<String> = emptyList(),
)

@Serializable
enum class SkipKind(val label: String) {
    INTRO("Salta Sigla"),
    OUTRO("Salta Outro"),
    RECAP("Salta Riepilogo"),
}

@Serializable
data class SkipSegment(
    val kind: SkipKind,
    val startMs: Long,
    val endMs: Long,
    val source: String = "vixcloud",
    val verified: Boolean = true,
) {
    fun contains(positionMs: Long): Boolean =
        positionMs in startMs..(endMs - SKIP_END_MARGIN_MS).coerceAtLeast(startMs)
}

@Serializable
data class AnimeItem(
    val id: String,
    val slug: String = "",
    val title: String,
    val titleJa: String? = null,
    val titleIt: String? = null,
    val type: String = "TV",
    val season: String = "",
    val year: Int? = null,
    val totalEpisodes: Int? = null,
    val runtimeMinutes: Int? = null,
    val status: String = AnimeStatus.UNKNOWN.label,
    val score: String? = null,
    val views: Long = 0L,
    val posterUrl: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val plot: String = "",
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val anilistId: Int = 0,
    val malId: Int = 0,
    val isDub: Boolean = false,
    val audioLabel: String = "Giapponese",
    val releasedEpisodes: Int = 0,
    val latestEpisodeNum: String? = null,
    val latestVideoId: String? = null,
    val hasSubVariant: Boolean = true,
    val hasDubVariant: Boolean = false,
) {
    val uniqueKey: String get() = "${id}_${if (isDub) "ita" else "sub"}"

    val animeStatus: AnimeStatus get() = AnimeStatus.fromRaw(status)

    val displayImageUrl: String?
        get() = posterUrl?.takeIf { it.isNotBlank() }
            ?: coverUrl?.takeIf { it.isNotBlank() }
            ?: bannerUrl?.takeIf { it.isNotBlank() }

    val displayBannerUrl: String?
        get() = bannerUrl?.takeIf { it.isNotBlank() }
            ?: coverUrl?.takeIf { it.isNotBlank() }
            ?: posterUrl?.takeIf { it.isNotBlank() }
}

@Serializable
data class EpisodeItem(
    val episodeId: String = "",
    val number: String,
    val videoId: String,
    val language: String = "jpn",
    val runtimeSeconds: Int = 0,
    val scwsId: String = "",
    val isFiller: Boolean = false,
)

data class StreamCandidate(
    val url: String,
    val isHls: Boolean,
    val qualityLabel: String = "1080p",
)

data class StreamResolution(
    val candidates: List<StreamCandidate>,
    val downloadUrl: String?,
    val referer: String,
    val qualityLabel: String = "1080p",
    val skipSegments: List<SkipSegment> = emptyList(),
)

data class DirectDownloadSource(
    val url: String,
    val referer: String,
)

@Serializable
data class EpisodeProgress(
    val episodeNum: String,
    val videoId: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val fraction: Float
        get() = when {
            completed -> 1f
            durationMs > 0L -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

@Serializable
data class WatchHistoryEntry(
    val anime: AnimeItem,
    val currentEpisodeNum: String,
    val currentVideoId: String,
    val progressMs: Long = 0L,
    val durationMs: Long = 0L,
    val completed: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val episodesProgress: Map<String, EpisodeProgress> = emptyMap(),
) {
    val uniqueKey: String get() = anime.uniqueKey

    val progressFraction: Float
        get() = when {
            completed -> 1f
            durationMs > 0L -> (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

data class ResumeTarget(
    val episodeIndex: Int,
    val episode: EpisodeItem,
    val startPositionMs: Long,
    val advancesToNextEpisode: Boolean,
    val replaysCompletedLastEpisode: Boolean,
) {
    fun detailCtaLabel(): String = when {
        advancesToNextEpisode -> "Guarda Prossimo (Ep. ${episode.number})"
        replaysCompletedLastEpisode -> "Riguarda Ep. ${episode.number}"
        startPositionMs > 0L -> "Riprendi Ep. ${episode.number} (${formatTimeMs(startPositionMs)})"
        else -> "Guarda Episodio ${episode.number}"
    }
}

fun resolveResumeTarget(
    episodes: List<EpisodeItem>,
    historyEntry: WatchHistoryEntry?,
): ResumeTarget? {
    if (episodes.isEmpty()) return null
    if (historyEntry == null) {
        return ResumeTarget(
            episodeIndex = 0,
            episode = episodes.first(),
            startPositionMs = 0L,
            advancesToNextEpisode = false,
            replaysCompletedLastEpisode = false,
        )
    }

    val matchedIdx = episodes.indexOfFirst { it.number == historyEntry.currentEpisodeNum }
    if (matchedIdx < 0) {
        return ResumeTarget(
            episodeIndex = 0,
            episode = episodes.first(),
            startPositionMs = 0L,
            advancesToNextEpisode = false,
            replaysCompletedLastEpisode = false,
        )
    }

    if (historyEntry.completed) {
        val hasNext = matchedIdx + 1 < episodes.size
        val targetIdx = if (hasNext) matchedIdx + 1 else matchedIdx
        return ResumeTarget(
            episodeIndex = targetIdx,
            episode = episodes[targetIdx],
            startPositionMs = 0L,
            advancesToNextEpisode = hasNext,
            replaysCompletedLastEpisode = !hasNext,
        )
    }

    return ResumeTarget(
        episodeIndex = matchedIdx,
        episode = episodes[matchedIdx],
        startPositionMs = historyEntry.progressMs.coerceAtLeast(0L),
        advancesToNextEpisode = false,
        replaysCompletedLastEpisode = false,
    )
}

@Serializable
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
}

@Serializable
data class DownloadEntry(
    val key: String,
    val animeId: String,
    val animeTitle: String,
    val posterUrl: String? = null,
    val isDub: Boolean = false,
    val episodeNum: String,
    val videoId: String,
    val filePath: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progressFraction: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            totalBytes > 0L -> (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }
}

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val changelog: String,
    val downloadUrl: String,
    val sha256Url: String? = null,
    val sizeBytes: Long,
    val htmlUrl: String,
)

@Serializable
data class AppBackupData(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings = AppSettings(),
    val history: List<WatchHistoryEntry> = emptyList(),
    val favorites: List<AnimeItem> = emptyList(),
)

fun formatTimeMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
