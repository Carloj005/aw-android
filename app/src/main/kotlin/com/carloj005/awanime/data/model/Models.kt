package com.carloj005.awanime.data.model

import kotlinx.serialization.Serializable

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
) {
    fun contains(positionMs: Long): Boolean = positionMs in startMs..(endMs - 800L).coerceAtLeast(startMs)
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
    val status: String = "Sconosciuto",
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
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format("%.2f GB", mb / 1024.0)
    } else {
        String.format("%.1f MB", mb)
    }
}
