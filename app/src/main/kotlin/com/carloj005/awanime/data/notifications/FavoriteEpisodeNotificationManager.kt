package com.carloj005.awanime.data.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.carloj005.awanime.MainActivity
import com.carloj005.awanime.R
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AnimeStatus
import com.carloj005.awanime.data.model.DiscoveredSequel
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.NewEpisodeAlert
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.storage.FavoritesRepository
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.storage.JsonFileStore
import com.carloj005.awanime.data.storage.SettingsRepository
import com.carloj005.awanime.data.storage.TrackedNotificationsRepository
import com.carloj005.awanime.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.TimeUnit

data class EpisodeSnapshot(
    val releasedCount: Int,
    val latestEpisodeLabel: String? = null,
)

data class FavoriteCheckSummary(
    val episodeAlerts: List<NewEpisodeAlert> = emptyList(),
    val discoveredSequels: List<DiscoveredSequel> = emptyList(),
    val monitoredCount: Int = 0,
)

/**
 * Pure, deterministic detector for newly released episodes of tracked anime.
 * - First observation of an anime silently seeds its baseline (never spams old episodes).
 * - Only emits an alert when the fresh episode count strictly exceeds the stored baseline.
 */
object FavoriteEpisodeUpdateDetector {

    fun detectNewEpisodes(
        favorites: List<AnimeItem>,
        storedBaseline: Map<String, Int>,
        freshSnapshots: Map<String, EpisodeSnapshot>,
    ): Pair<List<NewEpisodeAlert>, Map<String, Int>> {
        val updatedBaseline = LinkedHashMap(storedBaseline)
        val alerts = mutableListOf<NewEpisodeAlert>()

        for (fav in favorites) {
            val key = fav.uniqueKey
            val snapshot = freshSnapshots[key]
            val observedCount = maxOf(
                snapshot?.releasedCount ?: 0,
                fav.releasedEpisodes,
            )
            if (observedCount <= 0) continue

            val previousCount = storedBaseline[key]
            if (previousCount == null) {
                updatedBaseline[key] = observedCount
            } else if (observedCount > previousCount) {
                updatedBaseline[key] = observedCount
                val epLabel = snapshot?.latestEpisodeLabel
                    ?.takeIf { it.isNotBlank() }
                    ?: observedCount.toString()
                alerts.add(
                    NewEpisodeAlert(
                        anime = fav.copy(releasedEpisodes = observedCount),
                        previousCount = previousCount,
                        newCount = observedCount,
                        episodeLabel = epLabel,
                    )
                )
            } else {
                updatedBaseline[key] = maxOf(previousCount, observedCount)
            }
        }

        val activeKeys = favorites.map { it.uniqueKey }.toSet()
        updatedBaseline.keys.retainAll(activeKeys)

        return alerts to updatedBaseline
    }
}

/**
 * Manages periodic background checks and local Android notifications strictly for the anime the user
 * chose to track (Favorites + actively watched + explicitly bell-enabled, minus muted anime), AND
 * automatically detects & saves new seasons/sequels of franchises the user watched or finished.
 */
class FavoriteEpisodeNotificationManager(
    private val context: Context,
    private val client: AnimeUnityClient,
    private val favoritesRepo: FavoritesRepository,
    private val historyRepo: HistoryRepository,
    private val trackedRepo: TrackedNotificationsRepository,
    private val settingsRepo: SettingsRepository,
    filesDir: File = context.filesDir,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        const val PERIODIC_WORK_NAME = "aw_favorite_episodes_periodic_check"
        const val CHANNEL_ID = "aw_new_favorite_episodes"
        private const val MAX_DIRECT_DETAIL_PROBES = 12
    }

    private val baselineStore = JsonFileStore(
        file = File(filesDir, "favorite_episode_baseline.json"),
        serializer = MapSerializer(String.serializer(), Int.serializer()),
        defaultValue = emptyMap(),
        ioDispatcher = ioDispatcher,
    )

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    fun syncPeriodicSchedule(enabled: Boolean, intervalHours: Int = 3) {
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val safeHours = intervalHours.coerceIn(1, 24).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<FavoriteEpisodesCheckWorker>(
            safeHours,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun recordKnownEpisodeCount(anime: AnimeItem, episodeCount: Int) {
        val safeCount = maxOf(episodeCount, anime.releasedEpisodes)
        if (safeCount <= 0) return
        baselineStore.update { current ->
            val prev = current[anime.uniqueKey] ?: 0
            if (safeCount > prev) {
                current + (anime.uniqueKey to safeCount)
            } else {
                current
            }
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    suspend fun checkFavoritesForNewEpisodes(
        respectSettingToggle: Boolean = true,
        postNotifications: Boolean = true,
    ): FavoriteCheckSummary = withContext(Dispatchers.IO) {
        val settings = settingsRepo.awaitLoaded()
        if (respectSettingToggle && !settings.notifyFavoriteEpisodes) {
            return@withContext FavoriteCheckSummary()
        }

        val favorites = favoritesRepo.awaitLoaded()
        val historyEntries = historyRepo.awaitLoaded()
        val notifPrefs = trackedRepo.awaitLoaded()

        val favoriteKeys = favorites.map { it.uniqueKey }.toSet()
        val watchingKeys = historyEntries.map { it.uniqueKey }.toSet()

        // Pool of all known user anime (Favorites + Watch History)
        val userLibraryByKey = LinkedHashMap<String, AnimeItem>()
        for (fav in favorites) userLibraryByKey[fav.uniqueKey] = fav
        for (h in historyEntries) userLibraryByKey.putIfAbsent(h.uniqueKey, h.anime)

        // Monitored list: ONLY anime where notifications are enabled (respects per-anime bell/mute!)
        val monitoredAnime = userLibraryByKey.values.filter { anime ->
            TrackedNotificationsRepository.isEnabledForAnime(
                prefs = notifPrefs,
                uniqueKey = anime.uniqueKey,
                favoriteKeys = favoriteKeys,
                watchingKeys = watchingKeys,
            )
        }
        if (monitoredAnime.isEmpty() && userLibraryByKey.isEmpty()) {
            return@withContext FavoriteCheckSummary()
        }

        val storedBaseline = baselineStore.awaitLoaded()
        val snapshots = LinkedHashMap<String, EpisodeSnapshot>()
        val feedCandidates = mutableListOf<AnimeItem>()

        // 1. Fast sweep: latest episodes feed
        val latestItems = runCatchingCancellable { client.getLatestEpisodes(LatestFilter.ALL) }
            .getOrDefault(emptyList())
        feedCandidates.addAll(latestItems)
        for (item in latestItems) {
            val latestInt = item.latestEpisodeNum?.trim()?.split(".", "-")?.firstOrNull()?.toIntOrNull() ?: 0
            val count = maxOf(item.releasedEpisodes, latestInt)
            if (count > 0) {
                val prev = snapshots[item.uniqueKey]
                if (prev == null || count > prev.releasedCount) {
                    snapshots[item.uniqueKey] = EpisodeSnapshot(
                        releasedCount = count,
                        latestEpisodeLabel = item.latestEpisodeNum ?: count.toString(),
                    )
                }
            }
        }

        // 2. Fast sweep: weekly calendar endpoint
        val calendarGroups = runCatchingCancellable { client.getWeeklyCalendar() }
            .getOrDefault(emptyList())
        for (group in calendarGroups) {
            for (entry in group.entries) {
                feedCandidates.add(entry.anime)
                val airedInt = entry.airedEpisodeNumber?.trim()?.split(".", "-")?.firstOrNull()?.toIntOrNull() ?: 0
                val count = maxOf(entry.anime.releasedEpisodes, airedInt)
                if (count > 0) {
                    val prev = snapshots[entry.anime.uniqueKey]
                    if (prev == null || count > prev.releasedCount) {
                        snapshots[entry.anime.uniqueKey] = EpisodeSnapshot(
                            releasedCount = count,
                            latestEpisodeLabel = entry.airedEpisodeNumber ?: count.toString(),
                        )
                    }
                }
            }
        }

        // 3. Auto-discover new seasons / sequels for any franchise in the user's library (e.g. Solo Leveling S3)
        val discoveredSequels = SequelTracker.detectNewSeasonsInFeed(
            userLibrary = userLibraryByKey.values.toList(),
            feedCandidates = feedCandidates,
            knownUserKeys = userLibraryByKey.keys,
            alreadyDiscoveredKeys = notifPrefs.discoveredSequelKeys,
        )
        for (seq in discoveredSequels) {
            if (!favoritesRepo.isFavorite(seq.newSeason.uniqueKey)) {
                favoritesRepo.toggleFavorite(seq.newSeason)
            }
            trackedRepo.setAnimeNotificationEnabled(seq.newSeason.uniqueKey, enabled = true)
            trackedRepo.markSequelDiscovered(seq.newSeason.uniqueKey)
            recordKnownEpisodeCount(seq.newSeason, seq.newSeason.releasedEpisodes)
        }

        // 4. Targeted detail probe for ongoing monitored anime not matched above
        var probes = 0
        for (anime in monitoredAnime) {
            if (probes >= MAX_DIRECT_DETAIL_PROBES) break
            if (anime.uniqueKey in snapshots) continue
            if (anime.animeStatus == AnimeStatus.COMPLETED) continue
            probes++
            val fresh = runCatchingCancellable { client.getAnimeDetails(anime.id, anime.isDub) }.getOrNull()
            if (fresh != null && fresh.releasedEpisodes > 0) {
                snapshots[anime.uniqueKey] = EpisodeSnapshot(
                    releasedCount = fresh.releasedEpisodes,
                    latestEpisodeLabel = fresh.releasedEpisodes.toString(),
                )
            }
        }

        val (alerts, updatedBaseline) = FavoriteEpisodeUpdateDetector.detectNewEpisodes(
            favorites = monitoredAnime,
            storedBaseline = storedBaseline,
            freshSnapshots = snapshots,
        )

        if (updatedBaseline != storedBaseline) {
            baselineStore.update { updatedBaseline }
            baselineStore.flush()
        }

        if (postNotifications && (alerts.isNotEmpty() || discoveredSequels.isNotEmpty()) && hasNotificationPermission()) {
            ensureNotificationChannel()
            alerts.forEach { alert -> postNewEpisodeNotification(alert) }
            discoveredSequels.forEach { seq -> postNewSeasonNotification(seq) }
        }

        FavoriteCheckSummary(
            episodeAlerts = alerts,
            discoveredSequels = discoveredSequels,
            monitoredCount = monitoredAnime.size,
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Nuovi episodi & Stagioni",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Avvisi per nuovi episodi e nuove stagioni degli anime seguiti"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun postNewEpisodeNotification(alert: NewEpisodeAlert) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val pendingIntent = buildAnimePendingIntent(alert.anime, offset = 2000)
        val langBadge = if (alert.anime.isDub) "ITA" else "SUB"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nuovo episodio: ${alert.anime.title}")
            .setContentText("Episodio ${alert.episodeLabel} disponibile ($langBadge) — Tocca per guardare")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        val reqCode = (alert.anime.uniqueKey.hashCode() and 0x7FFFFFFF).coerceAtLeast(2000)
        nm.notify(reqCode, notification)
    }

    private fun postNewSeasonNotification(sequel: DiscoveredSequel) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val pendingIntent = buildAnimePendingIntent(sequel.newSeason, offset = 5000)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nuova stagione trovata & salvata!")
            .setContentText("${sequel.newSeason.title} è ora su AnimeUnity ed è stata aggiunta ai tuoi Preferiti.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        val reqCode = ((sequel.newSeason.uniqueKey + "_sequel").hashCode() and 0x7FFFFFFF).coerceAtLeast(5000)
        nm.notify(reqCode, notification)
    }

    private fun buildAnimePendingIntent(anime: AnimeItem, offset: Int): PendingIntent {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ANIME_ID, anime.id)
            putExtra(MainActivity.EXTRA_OPEN_ANIME_TITLE, anime.title)
            putExtra(MainActivity.EXTRA_OPEN_ANIME_IS_DUB, anime.isDub)
        }
        val reqCode = (anime.uniqueKey.hashCode() and 0x7FFFFFFF).coerceAtLeast(offset)
        return PendingIntent.getActivity(
            context,
            reqCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
