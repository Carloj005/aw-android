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
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.NewEpisodeAlert
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.storage.FavoritesRepository
import com.carloj005.awanime.data.storage.JsonFileStore
import com.carloj005.awanime.data.storage.SettingsRepository
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

/**
 * Pure, deterministic detector for newly released episodes of favorited anime.
 * - First observation of a favorite silently seeds its baseline (never spams old episodes).
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
                // First time seeing this favorite: establish baseline without firing a false notification.
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

        // Prune baselines for anime no longer in favorites to keep storage lean.
        val activeKeys = favorites.map { it.uniqueKey }.toSet()
        updatedBaseline.keys.retainAll(activeKeys)

        return alerts to updatedBaseline
    }
}

/**
 * Manages periodic background checks and local Android notifications for new episodes
 * of anime saved in the user's Favorites.
 */
class FavoriteEpisodeNotificationManager(
    private val context: Context,
    private val client: AnimeUnityClient,
    private val favoritesRepo: FavoritesRepository,
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
    ): List<NewEpisodeAlert> = withContext(Dispatchers.IO) {
        val settings = settingsRepo.awaitLoaded()
        if (respectSettingToggle && !settings.notifyFavoriteEpisodes) {
            return@withContext emptyList()
        }

        val favorites = favoritesRepo.awaitLoaded()
        if (favorites.isEmpty()) return@withContext emptyList()

        val storedBaseline = baselineStore.awaitLoaded()
        val snapshots = LinkedHashMap<String, EpisodeSnapshot>()

        // 1. Fast sweep: latest episodes feed (1-2 API calls, covers newly dropped SUB & ITA episodes)
        val latestItems = runCatchingCancellable { client.getLatestEpisodes(LatestFilter.ALL) }
            .getOrDefault(emptyList())
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

        // 2. Fast sweep: weekly calendar endpoint (1 API call, covers airing seasonal anime)
        val calendarGroups = runCatchingCancellable { client.getWeeklyCalendar() }
            .getOrDefault(emptyList())
        for (group in calendarGroups) {
            for (entry in group.entries) {
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

        // 3. Targeted detail probe for ongoing favorites not matched above (bounded to save battery)
        var probes = 0
        for (fav in favorites) {
            if (probes >= MAX_DIRECT_DETAIL_PROBES) break
            if (fav.uniqueKey in snapshots) continue
            if (fav.animeStatus == AnimeStatus.COMPLETED) continue
            probes++
            val fresh = runCatchingCancellable { client.getAnimeDetails(fav.id, fav.isDub) }.getOrNull()
            if (fresh != null && fresh.releasedEpisodes > 0) {
                snapshots[fav.uniqueKey] = EpisodeSnapshot(
                    releasedCount = fresh.releasedEpisodes,
                    latestEpisodeLabel = fresh.releasedEpisodes.toString(),
                )
            }
        }

        val (alerts, updatedBaseline) = FavoriteEpisodeUpdateDetector.detectNewEpisodes(
            favorites = favorites,
            storedBaseline = storedBaseline,
            freshSnapshots = snapshots,
        )

        if (updatedBaseline != storedBaseline) {
            baselineStore.update { updatedBaseline }
            baselineStore.flush()
        }

        if (postNotifications && alerts.isNotEmpty() && hasNotificationPermission()) {
            ensureNotificationChannel()
            alerts.forEach { alert -> postNewEpisodeNotification(alert) }
        }

        alerts
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Nuovi episodi Preferiti",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Avvisi quando esce un nuovo episodio di un anime nei tuoi Preferiti"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun postNewEpisodeNotification(alert: NewEpisodeAlert) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ANIME_ID, alert.anime.id)
            putExtra(MainActivity.EXTRA_OPEN_ANIME_TITLE, alert.anime.title)
            putExtra(MainActivity.EXTRA_OPEN_ANIME_IS_DUB, alert.anime.isDub)
        }
        val reqCode = (alert.anime.uniqueKey.hashCode() and 0x7FFFFFFF).coerceAtLeast(2000)
        val pendingIntent = PendingIntent.getActivity(
            context,
            reqCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val langBadge = if (alert.anime.isDub) "ITA" else "SUB"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nuovo episodio: ${alert.anime.title}")
            .setContentText("Episodio ${alert.episodeLabel} disponibile ($langBadge) — Tocca per guardare")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(reqCode, notification)
    }
}
