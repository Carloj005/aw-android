package com.carloj005.awanime.data.storage

import com.carloj005.awanime.data.model.TrackedNotificationPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Manages per-anime notification opt-in/opt-out preferences and discovered sequel tracking.
 *
 * Rules:
 * - An anime is enabled if explicitly toggled ON, OR if it is in the user's Favorites / active
 *   Watch History and has NOT been explicitly muted.
 * - Toggling the bell icon on any anime overrides the default cleanly for that specific anime.
 */
class TrackedNotificationsRepository(
    filesDir: File,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val store = JsonFileStore(
        file = File(filesDir, "tracked_notifications.json"),
        serializer = TrackedNotificationPrefs.serializer(),
        defaultValue = TrackedNotificationPrefs(),
        ioDispatcher = ioDispatcher,
    )

    val prefs: StateFlow<TrackedNotificationPrefs> = store.state

    suspend fun awaitLoaded(): TrackedNotificationPrefs = store.awaitLoaded()

    fun isEnabledForAnime(
        uniqueKey: String,
        favoriteKeys: Set<String>,
        watchingKeys: Set<String>,
    ): Boolean {
        return isEnabledForAnime(store.value, uniqueKey, favoriteKeys, watchingKeys)
    }

    fun setAnimeNotificationEnabled(uniqueKey: String, enabled: Boolean) {
        store.update { current ->
            if (enabled) {
                current.copy(
                    explicitlyEnabledKeys = current.explicitlyEnabledKeys + uniqueKey,
                    explicitlyMutedKeys = current.explicitlyMutedKeys - uniqueKey,
                )
            } else {
                current.copy(
                    explicitlyEnabledKeys = current.explicitlyEnabledKeys - uniqueKey,
                    explicitlyMutedKeys = current.explicitlyMutedKeys + uniqueKey,
                )
            }
        }
    }

    fun markSequelDiscovered(uniqueKey: String) {
        store.update { current ->
            if (uniqueKey in current.discoveredSequelKeys) {
                current
            } else {
                current.copy(discoveredSequelKeys = current.discoveredSequelKeys + uniqueKey)
            }
        }
    }

    fun mergeImport(imported: TrackedNotificationPrefs) {
        store.update { current ->
            current.copy(
                explicitlyEnabledKeys = current.explicitlyEnabledKeys + imported.explicitlyEnabledKeys,
                explicitlyMutedKeys = (current.explicitlyMutedKeys + imported.explicitlyMutedKeys) - imported.explicitlyEnabledKeys,
                discoveredSequelKeys = current.discoveredSequelKeys + imported.discoveredSequelKeys,
            )
        }
    }

    suspend fun flush() = store.flush()

    companion object {
        fun isEnabledForAnime(
            prefs: TrackedNotificationPrefs,
            uniqueKey: String,
            favoriteKeys: Set<String>,
            watchingKeys: Set<String>,
        ): Boolean {
            if (uniqueKey in prefs.explicitlyMutedKeys) return false
            if (uniqueKey in prefs.explicitlyEnabledKeys) return true
            return uniqueKey in favoriteKeys || uniqueKey in watchingKeys
        }
    }
}
