package com.carloj005.awanime.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.carloj005.awanime.AppContainer
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.BuildConfig
import com.carloj005.awanime.data.integrations.AnimeUnityDeepLinkParser
import com.carloj005.awanime.data.integrations.ArmSeasonMapping
import com.carloj005.awanime.data.integrations.BugReportHelper
import com.carloj005.awanime.data.integrations.BugReportSubmitResult
import com.carloj005.awanime.data.integrations.ReportKind
import com.carloj005.awanime.data.integrations.ResolvedSuiteTvTarget
import com.carloj005.awanime.data.integrations.SuiteTvImportedShow
import com.carloj005.awanime.data.integrations.SuiteTvIntegration
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AppBackupData
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.CalendarDayGroup
import com.carloj005.awanime.data.model.DiscoveredSequel
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.EpisodeProgress
import com.carloj005.awanime.data.model.GenreItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.SearchFilter
import com.carloj005.awanime.data.model.SearchSortOrder
import com.carloj005.awanime.data.model.SearchTypeFilter
import com.carloj005.awanime.data.model.TrackedNotificationPrefs
import com.carloj005.awanime.data.model.UpdateInfo
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.resolveResumeTarget
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.network.OfflineException
import com.carloj005.awanime.data.network.RateLimitedException
import com.carloj005.awanime.data.network.SearchUtils
import com.carloj005.awanime.data.notifications.FavoriteEpisodeNotificationManager
import com.carloj005.awanime.data.notifications.SequelTracker
import com.carloj005.awanime.data.storage.DownloadRepository
import com.carloj005.awanime.data.storage.FavoritesRepository
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.storage.SettingsRepository
import com.carloj005.awanime.data.storage.TrackedNotificationsRepository
import com.carloj005.awanime.data.update.AppUpdater
import com.carloj005.awanime.ui.screens.SettingsEvent
import com.carloj005.awanime.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class CalendarFilter(val label: String) {
    MY_ANIME("I miei"),
    ALL("Tutti"),
    SUB("SUB"),
    DUB("ITA"),
}

data class HomeUiState(
    val selectedFilter: LatestFilter = LatestFilter.ALL,
    val heroCarousel: List<AnimeItem> = emptyList(),
    val items: List<AnimeItem> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val activeDomain: String = "animeunity.cx",
)

data class CalendarUiState(
    val days: List<CalendarDayGroup> = emptyList(),
    val selectedDayIndex: Int = 0,
    val filter: CalendarFilter = CalendarFilter.MY_ANIME,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val error: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val expandedAliasHint: String? = null,
    val filter: SearchFilter = SearchFilter.ALL,
    val typeFilter: SearchTypeFilter = SearchTypeFilter.ALL,
    val sortOrder: SearchSortOrder = SearchSortOrder.CHRONOLOGICAL,
    val genres: List<GenreItem> = emptyList(),
    val selectedGenreId: Int? = null,
    val isLoadingGenres: Boolean = false,
    val results: List<AnimeItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val recentQueries: List<String> = emptyList(),
)

data class DetailUiState(
    val anime: AnimeItem? = null,
    val episodes: List<EpisodeItem> = emptyList(),
    val relatedAnimes: List<AnimeItem> = emptyList(),
    val nextSeason: AnimeItem? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class RandomAnimeUiState(
    val isLoading: Boolean = false,
    val anime: AnimeItem? = null,
    val error: String? = null,
    val showDialog: Boolean = false,
)

data class PlayerLaunchRequest(
    val anime: AnimeItem,
    val episodes: List<EpisodeItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

data class UpdateUiState(
    val availableUpdate: UpdateInfo? = null,
    val showDialog: Boolean = false,
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val statusMessage: String? = null,
    val needsInstallPermission: Boolean = false,
    val pendingApkFile: File? = null,
)

class AppViewModel(
    private val client: AnimeUnityClient,
    private val thirdPartyHttpClient: OkHttpClient,
    private val historyRepo: HistoryRepository,
    private val favoritesRepo: FavoritesRepository,
    private val trackedRepo: TrackedNotificationsRepository,
    private val downloadRepo: DownloadRepository,
    private val settingsRepo: SettingsRepository,
    private val notificationManager: FavoriteEpisodeNotificationManager,
    private val updater: AppUpdater,
    private val cacheDir: File,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val KEY_SAVED_DETAIL_ID = "saved_detail_id"
        private const val KEY_SAVED_DETAIL_TITLE = "saved_detail_title"
        private const val KEY_SAVED_DETAIL_DUB = "saved_detail_dub"
        private const val MAX_RECENT_QUERIES = 10
        private const val SEARCH_DEBOUNCE_MS = 450L
        private const val MAX_BACKUP_IMPORT_BYTES = 10 * 1024 * 1024 // 10 MB anti-OOM/ANR cap
        private const val MAX_SUITETV_IMPORT_BYTES = 20 * 1024 * 1024 // 20 MB cap for Suite TV .zip archives
        private const val MAX_SUITETV_IMPORT_GROUPS = 250

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AwAnimeApp
                val container: AppContainer = app.container
                AppViewModel(
                    client = container.animeUnityClient,
                    thirdPartyHttpClient = container.httpClients.externalClient,
                    historyRepo = container.historyRepository,
                    favoritesRepo = container.favoritesRepository,
                    trackedRepo = container.trackedNotificationsRepository,
                    downloadRepo = container.downloadRepository,
                    settingsRepo = container.settingsRepository,
                    notificationManager = container.favoriteNotificationManager,
                    updater = container.appUpdater,
                    cacheDir = container.cacheDir,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }

    val history: StateFlow<List<WatchHistoryEntry>> = historyRepo.history
    val favorites: StateFlow<List<AnimeItem>> = favoritesRepo.favorites
    val trackedNotificationPrefs: StateFlow<TrackedNotificationPrefs> = trackedRepo.prefs
    val downloads: StateFlow<List<DownloadEntry>> = downloadRepo.downloads
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    val shareBaseUrl: String get() = client.baseUrl

    private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val homeCache = mutableMapOf<LatestFilter, List<AnimeItem>>()
    private var homeJob: Job? = null
    private var calendarJob: Job? = null
    private var searchJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var genresJob: Job? = null
    private var detailJob: Job? = null
    private var randomJob: Job? = null
    private var playbackLaunchJob: Job? = null
    private var updateJob: Job? = null
    private var favoriteCheckJob: Job? = null

    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _calendarState = MutableStateFlow(CalendarUiState())
    val calendarState: StateFlow<CalendarUiState> = _calendarState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _detailState = MutableStateFlow(DetailUiState())
    val detailState: StateFlow<DetailUiState> = _detailState.asStateFlow()

    private val _randomAnimeState = MutableStateFlow(RandomAnimeUiState())
    val randomAnimeState: StateFlow<RandomAnimeUiState> = _randomAnimeState.asStateFlow()

    private val _discoveredSequels = MutableStateFlow<List<DiscoveredSequel>>(emptyList())
    val discoveredSequels: StateFlow<List<DiscoveredSequel>> = _discoveredSequels.asStateFlow()

    private val _activePlayer = MutableStateFlow<PlayerLaunchRequest?>(null)
    val activePlayer: StateFlow<PlayerLaunchRequest?> = _activePlayer.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _settingsFeedback = MutableStateFlow<String?>(null)
    val settingsFeedback: StateFlow<String?> = _settingsFeedback.asStateFlow()

    private val _isCheckingFavorites = MutableStateFlow(false)
    val isCheckingFavorites: StateFlow<Boolean> = _isCheckingFavorites.asStateFlow()

    private val _isImportingSuiteTv = MutableStateFlow(false)
    val isImportingSuiteTv: StateFlow<Boolean> = _isImportingSuiteTv.asStateFlow()

    private val _isExportingSuiteTv = MutableStateFlow(false)
    val isExportingSuiteTv: StateFlow<Boolean> = _isExportingSuiteTv.asStateFlow()

    private val _suiteTvSyncProgress = MutableStateFlow<String?>(null)
    val suiteTvSyncProgress: StateFlow<String?> = _suiteTvSyncProgress.asStateFlow()

    private val _isSubmittingBugReport = MutableStateFlow(false)
    val isSubmittingBugReport: StateFlow<Boolean> = _isSubmittingBugReport.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    init {
        loadHome(LatestFilter.ALL)
        restoreDetailIfNeeded()
        viewModelScope.launch {
            val loadedSettings = settingsRepo.awaitLoaded()
            _searchState.update { it.copy(recentQueries = loadedSettings.recentQueries) }
            notificationManager.syncPeriodicSchedule(
                enabled = loadedSettings.notifyFavoriteEpisodes,
                intervalHours = loadedSettings.notifyCheckIntervalHours,
            )
            if (loadedSettings.autoCheckUpdates) {
                checkForUpdates(manual = false)
            }
        }
    }

    private fun restoreDetailIfNeeded() {
        val savedId = savedStateHandle.get<String>(KEY_SAVED_DETAIL_ID)?.takeIf { it.isNotBlank() } ?: return
        val savedTitle = savedStateHandle.get<String>(KEY_SAVED_DETAIL_TITLE).orEmpty().ifBlank { "Anime" }
        val savedDub = savedStateHandle.get<Boolean>(KEY_SAVED_DETAIL_DUB) ?: false
        openAnimeById(savedId, savedTitle, savedDub)
    }

    fun openAnimeById(animeId: String, title: String, isDub: Boolean, slug: String = "") {
        if (animeId.isBlank()) return
        val resolvedTitle = title.ifBlank { AnimeUnityDeepLinkParser.slugToDisplayTitle(slug) }
        openAnimeDetail(
            AnimeItem(
                id = animeId,
                slug = slug,
                title = resolvedTitle,
                isDub = isDub,
                audioLabel = if (isDub) "Italiano" else "Giapponese",
            )
        )
    }

    /**
     * Resolves an incoming AnimeUnity direct episode link (`/anime/<id>-<slug>/<videoId>`):
     * shows the detail screen immediately while fetching episodes, matches `videoId` across both
     * SUB and ITA variants if needed, and starts playback directly on the linked episode.
     */
    fun openEpisodeFromDeepLink(
        animeId: String,
        slug: String,
        videoId: String,
        isDubHint: Boolean,
    ) {
        if (animeId.isBlank()) return
        detailJob?.cancel()
        playbackLaunchJob?.cancel()

        val placeholder = AnimeItem(
            id = animeId,
            slug = slug,
            title = AnimeUnityDeepLinkParser.slugToDisplayTitle(slug),
            isDub = isDubHint,
            audioLabel = if (isDubHint) "Italiano" else "Giapponese",
        )
        savedStateHandle[KEY_SAVED_DETAIL_ID] = animeId
        savedStateHandle[KEY_SAVED_DETAIL_TITLE] = placeholder.title
        savedStateHandle[KEY_SAVED_DETAIL_DUB] = isDubHint
        _detailState.value = DetailUiState(anime = placeholder, episodes = emptyList(), isLoading = true, error = null)

        detailJob = viewModelScope.launch {
            runCatchingCancellable {
                val primaryResult = runCatchingCancellable { client.getAnimeDetailsWithRelated(animeId, isDubHint) }.getOrNull()
                val primaryAnime = primaryResult?.anime ?: placeholder
                val primaryRelated = primaryResult?.relatedAnimes.orEmpty()
                val primaryEps = client.getEpisodes(primaryAnime.id, primaryAnime.isDub)
                val primaryIdx = primaryEps.indexOfFirst { it.videoId == videoId }
                if (primaryIdx >= 0) {
                    return@runCatchingCancellable Triple(primaryAnime to primaryRelated, primaryEps, primaryIdx)
                }

                // Shared episode link may belong to the alternate language variant (SUB vs ITA)
                val altDub = !isDubHint
                val hasAltVariant = if (altDub) primaryAnime.hasDubVariant else primaryAnime.hasSubVariant
                if (hasAltVariant) {
                    val altResult = runCatchingCancellable { client.getAnimeDetailsWithRelated(primaryAnime.id, altDub) }.getOrNull()
                    val altAnime = altResult?.anime
                        ?: primaryAnime.copy(isDub = altDub, audioLabel = if (altDub) "Italiano" else "Giapponese")
                    val altRelated = altResult?.relatedAnimes ?: primaryRelated
                    val altEps = runCatchingCancellable { client.getEpisodes(altAnime.id, altDub) }
                        .getOrDefault(emptyList())
                    val altIdx = altEps.indexOfFirst { it.videoId == videoId }
                    if (altIdx >= 0) {
                        return@runCatchingCancellable Triple(altAnime to altRelated, altEps, altIdx)
                    }
                }
                Triple(primaryAnime to primaryRelated, primaryEps, -1)
            }.onSuccess { (animeAndRelated, eps, matchedIdx) ->
                val (resolvedAnime, related) = animeAndRelated
                val nextSeason = SequelTracker.findNextSeason(resolvedAnime, related)
                savedStateHandle[KEY_SAVED_DETAIL_TITLE] = resolvedAnime.title
                savedStateHandle[KEY_SAVED_DETAIL_DUB] = resolvedAnime.isDub
                historyRepo.syncKnownEpisodeAvailability(
                    uniqueKey = resolvedAnime.uniqueKey,
                    availableEpisodes = eps.size,
                    lastEpisodeNum = eps.lastOrNull()?.number,
                )
                _detailState.value = DetailUiState(
                    anime = resolvedAnime,
                    episodes = eps,
                    relatedAnimes = related,
                    nextSeason = nextSeason,
                    isLoading = false,
                    error = null,
                )
                if (matchedIdx >= 0) {
                    startPlayback(resolvedAnime, eps, matchedIdx, resumeFromHistory = true)
                }
            }.onFailure { err ->
                _detailState.value = DetailUiState(
                    anime = placeholder,
                    episodes = emptyList(),
                    isLoading = false,
                    error = err.message ?: "Impossibile aprire il link episodio AnimeUnity",
                )
            }
        }
    }

    fun loadHome(filter: LatestFilter = _homeState.value.selectedFilter, forceRefresh: Boolean = false) {
        homeJob?.cancel()
        val cached = if (!forceRefresh) homeCache[filter] else null
        if (cached != null) {
            _homeState.update {
                it.copy(
                    selectedFilter = filter,
                    items = cached,
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                    activeDomain = client.baseUrl.removePrefix("https://"),
                )
            }
            return
        }

        _homeState.update {
            it.copy(
                selectedFilter = filter,
                isLoading = it.items.isEmpty() || !forceRefresh,
                isRefreshing = forceRefresh && it.items.isNotEmpty(),
                error = null,
            )
        }
        homeJob = viewModelScope.launch {
            runCatchingCancellable {
                val list = client.getLatestEpisodes(filter)
                val carousel = runCatchingCancellable { client.getHomeCarousel(forceRefresh = forceRefresh) }
                    .getOrDefault(emptyList())
                list to carousel
            }.onSuccess { (list, carousel) ->
                homeCache[filter] = list
                for (item in list) {
                    val latestInt = item.latestEpisodeNum?.trim()?.split(".", "-")?.firstOrNull()?.toIntOrNull() ?: 0
                    val avail = maxOf(item.releasedEpisodes, latestInt)
                    if (avail > 0) {
                        historyRepo.syncKnownEpisodeAvailability(
                            uniqueKey = item.uniqueKey,
                            availableEpisodes = avail,
                            lastEpisodeNum = item.latestEpisodeNum,
                        )
                    }
                }
                scanFeedForNewSeasons(list + carousel)
                _homeState.update { st ->
                    st.copy(
                        selectedFilter = filter,
                        heroCarousel = if (carousel.isNotEmpty()) carousel else st.heroCarousel,
                        items = list,
                        isLoading = false,
                        isRefreshing = false,
                        error = null,
                        activeDomain = client.baseUrl.removePrefix("https://"),
                    )
                }
            }.onFailure { err ->
                _homeState.update {
                    it.copy(
                        selectedFilter = filter,
                        isLoading = false,
                        isRefreshing = false,
                        error = err.message ?: "Errore di connessione ad AnimeUnity",
                    )
                }
            }
        }
    }

    fun ensureCalendarLoaded() {
        val st = _calendarState.value
        if (st.hasLoaded || st.isLoading) return
        loadCalendar(forceRefresh = false)
    }

    fun loadCalendar(forceRefresh: Boolean = false) {
        calendarJob?.cancel()
        _calendarState.update {
            it.copy(
                isLoading = it.days.isEmpty() || !forceRefresh,
                isRefreshing = forceRefresh && it.days.isNotEmpty(),
                error = null,
            )
        }
        calendarJob = viewModelScope.launch {
            runCatchingCancellable { client.getWeeklyCalendar() }
                .onSuccess { days ->
                    val allEntries = days.flatMap { it.entries }
                    for (entry in allEntries) {
                        val airedInt = entry.airedEpisodeNumber?.trim()?.split(".", "-")?.firstOrNull()?.toIntOrNull() ?: 0
                        val avail = maxOf(entry.anime.releasedEpisodes, airedInt)
                        if (avail > 0) {
                            historyRepo.syncKnownEpisodeAvailability(
                                uniqueKey = entry.anime.uniqueKey,
                                availableEpisodes = avail,
                                lastEpisodeNum = entry.airedEpisodeNumber,
                            )
                        }
                    }
                    scanFeedForNewSeasons(allEntries.map { it.anime })

                    val defaultDayIdx = days.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: 0
                    _calendarState.update { st ->
                        st.copy(
                            days = days,
                            selectedDayIndex = if (st.hasLoaded) {
                                st.selectedDayIndex.coerceIn(0, (days.size - 1).coerceAtLeast(0))
                            } else {
                                defaultDayIdx
                            },
                            isLoading = false,
                            isRefreshing = false,
                            hasLoaded = true,
                            error = null,
                        )
                    }
                }
                .onFailure { err ->
                    _calendarState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            error = err.message ?: "Impossibile caricare il calendario uscite",
                        )
                    }
                }
        }
    }

    /**
     * Checks live feed items against the user's library (Favorites + History): if a newer season
     * of a franchise the user watched/saved appears (e.g. Solo Leveling S3), it is automatically
     * saved to Favorites, enabled for notifications, and surfaced via the discovered-sequels banner.
     */
    private suspend fun scanFeedForNewSeasons(feedCandidates: List<AnimeItem>) {
        if (feedCandidates.isEmpty()) return
        val favs = favoritesRepo.awaitLoaded()
        val hist = historyRepo.awaitLoaded()
        val prefs = trackedRepo.awaitLoaded()

        val userLibraryByKey = LinkedHashMap<String, AnimeItem>()
        for (fav in favs) userLibraryByKey[fav.uniqueKey] = fav
        for (h in hist) userLibraryByKey.putIfAbsent(h.uniqueKey, h.anime)
        if (userLibraryByKey.isEmpty()) return

        val found = SequelTracker.detectNewSeasonsInFeed(
            userLibrary = userLibraryByKey.values.toList(),
            feedCandidates = feedCandidates,
            knownUserKeys = userLibraryByKey.keys,
            alreadyDiscoveredKeys = prefs.discoveredSequelKeys,
        )
        if (found.isEmpty()) return

        for (seq in found) {
            if (!favoritesRepo.isFavorite(seq.newSeason.uniqueKey)) {
                favoritesRepo.toggleFavorite(seq.newSeason)
            }
            trackedRepo.setAnimeNotificationEnabled(seq.newSeason.uniqueKey, enabled = true)
            trackedRepo.markSequelDiscovered(seq.newSeason.uniqueKey)
            notificationManager.recordKnownEpisodeCount(seq.newSeason, seq.newSeason.releasedEpisodes)
        }
        _discoveredSequels.update { existing ->
            val existingKeys = existing.map { it.newSeason.uniqueKey }.toSet()
            existing + found.filter { it.newSeason.uniqueKey !in existingKeys }
        }
    }

    fun dismissDiscoveredSequel(uniqueKey: String) {
        _discoveredSequels.update { list -> list.filterNot { it.newSeason.uniqueKey == uniqueKey } }
    }

    fun selectCalendarDay(index: Int) {
        _calendarState.update { st ->
            st.copy(selectedDayIndex = index.coerceIn(0, (st.days.size - 1).coerceAtLeast(0)))
        }
    }

    fun setCalendarFilter(filter: CalendarFilter) {
        _calendarState.update { it.copy(filter = filter) }
    }

    fun toggleAnimeNotification(anime: AnimeItem) {
        val favKeys = favorites.value.map { it.uniqueKey }.toSet()
        val watchingKeys = history.value.map { it.uniqueKey }.toSet()
        val currentlyEnabled = trackedRepo.isEnabledForAnime(anime.uniqueKey, favKeys, watchingKeys)
        val nextEnabled = !currentlyEnabled
        trackedRepo.setAnimeNotificationEnabled(anime.uniqueKey, nextEnabled)
        if (nextEnabled) {
            // Ensure anime tracked via bell from Calendar/Detail is also retained in Favorites so
            // background notification worker and personalized Calendar always include it.
            if (anime.uniqueKey !in favKeys && anime.uniqueKey !in watchingKeys) {
                favoritesRepo.toggleFavorite(anime)
            }
            val loadedEpCount = if (_detailState.value.anime?.uniqueKey == anime.uniqueKey) {
                _detailState.value.episodes.size
            } else {
                0
            }
            notificationManager.recordKnownEpisodeCount(anime, maxOf(loadedEpCount, anime.releasedEpisodes))
        }
    }

    fun ensureGenresLoaded() {
        val st = _searchState.value
        if (st.genres.isNotEmpty() || st.isLoadingGenres) return
        genresJob?.cancel()
        _searchState.update { it.copy(isLoadingGenres = true) }
        genresJob = viewModelScope.launch {
            runCatchingCancellable { client.getGenres() }
                .onSuccess { list ->
                    _searchState.update { it.copy(genres = list, isLoadingGenres = false) }
                }
                .onFailure {
                    _searchState.update { it.copy(isLoadingGenres = false) }
                }
        }
    }

    fun updateSearchQuery(newQuery: String) {
        searchDebounceJob?.cancel()
        _searchState.update { it.copy(query = newQuery) }
        val trimmed = newQuery.trim()
        val st = _searchState.value
        if (trimmed.isEmpty() && st.selectedGenreId == null && st.typeFilter == SearchTypeFilter.ALL) {
            searchJob?.cancel()
            _searchState.update {
                it.copy(
                    hasSearched = false,
                    results = emptyList(),
                    error = null,
                    expandedAliasHint = null,
                    isLoading = false,
                )
            }
            return
        }
        if (trimmed.length >= 3) {
            searchDebounceJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                performSearch(rawQuery = trimmed, saveToRecent = false)
            }
        } else if (trimmed.isEmpty() && (st.selectedGenreId != null || st.typeFilter != SearchTypeFilter.ALL)) {
            performSearch(rawQuery = "", saveToRecent = false)
        }
    }

    fun setSearchFilter(filter: SearchFilter) {
        _searchState.update { it.copy(filter = filter) }
    }

    fun selectSearchGenre(genreId: Int?) {
        val nextGenre = if (_searchState.value.selectedGenreId == genreId) null else genreId
        _searchState.update { it.copy(selectedGenreId = nextGenre) }
        val st = _searchState.value
        if (nextGenre != null || st.query.isNotBlank() || st.typeFilter != SearchTypeFilter.ALL) {
            performSearch(rawQuery = st.query, saveToRecent = false)
        } else {
            searchJob?.cancel()
            _searchState.update {
                it.copy(hasSearched = false, results = emptyList(), error = null, isLoading = false)
            }
        }
    }

    fun setSearchTypeFilter(typeFilter: SearchTypeFilter) {
        _searchState.update { it.copy(typeFilter = typeFilter) }
        val st = _searchState.value
        if (typeFilter != SearchTypeFilter.ALL || st.selectedGenreId != null || st.query.isNotBlank()) {
            performSearch(rawQuery = st.query, saveToRecent = false)
        } else {
            searchJob?.cancel()
            _searchState.update {
                it.copy(hasSearched = false, results = emptyList(), error = null, isLoading = false)
            }
        }
    }

    fun setSearchSortOrder(order: SearchSortOrder) {
        val prev = _searchState.value
        val activeQuery = prev.expandedAliasHint ?: prev.query
        val resorted = SearchUtils.sortByUserOrder(prev.results, activeQuery, order)
        _searchState.update { it.copy(sortOrder = order, results = resorted) }

        // If browsing archive by genre/type with no text query and selecting SCORE, fetch server-side top rated
        if (order == SearchSortOrder.SCORE &&
            prev.query.isBlank() &&
            (prev.selectedGenreId != null || prev.typeFilter != SearchTypeFilter.ALL)
        ) {
            performSearch(rawQuery = "", saveToRecent = false)
        }
    }

    fun clearSearchFilters() {
        searchDebounceJob?.cancel()
        val currentQuery = _searchState.value.query.trim()
        _searchState.update {
            it.copy(
                filter = SearchFilter.ALL,
                typeFilter = SearchTypeFilter.ALL,
                sortOrder = SearchSortOrder.CHRONOLOGICAL,
                selectedGenreId = null,
            )
        }
        if (currentQuery.isNotEmpty()) {
            performSearch(rawQuery = currentQuery, saveToRecent = false)
        } else {
            searchJob?.cancel()
            _searchState.update {
                it.copy(hasSearched = false, results = emptyList(), error = null, isLoading = false, expandedAliasHint = null)
            }
        }
    }

    fun clearRecentQueries() {
        settingsRepo.update { it.copy(recentQueries = emptyList()) }
        _searchState.update { it.copy(recentQueries = emptyList()) }
    }

    fun performSearch(rawQuery: String = _searchState.value.query, saveToRecent: Boolean = true) {
        searchDebounceJob?.cancel()
        val q = rawQuery.trim()
        val st = _searchState.value
        val hasArchiveFilters = st.selectedGenreId != null || st.typeFilter != SearchTypeFilter.ALL
        if (q.isEmpty() && !hasArchiveFilters) return

        searchJob?.cancel()
        val expanded = if (q.isNotEmpty()) SearchUtils.expandQueryAlias(q) else ""
        val aliasHint = if (q.isNotEmpty() && !expanded.equals(q, ignoreCase = true)) expanded else null

        val updatedRecent = if (saveToRecent && q.length >= 2) {
            (listOf(q) + st.recentQueries.filterNot { it.equals(q, ignoreCase = true) })
                .take(MAX_RECENT_QUERIES)
                .also { recent -> settingsRepo.update { it.copy(recentQueries = recent) } }
        } else {
            st.recentQueries
        }

        _searchState.update { cur ->
            cur.copy(
                query = q,
                expandedAliasHint = aliasHint,
                isLoading = true,
                hasSearched = true,
                error = null,
                recentQueries = updatedRecent,
            )
        }
        val genreId = st.selectedGenreId
        val typeFilter = st.typeFilter
        val sortOrder = st.sortOrder
        searchJob = viewModelScope.launch {
            runCatchingCancellable {
                val rawList = if (genreId != null || typeFilter != SearchTypeFilter.ALL || q.isEmpty()) {
                    client.browseArchive(
                        query = q,
                        genreId = genreId,
                        typeFilter = typeFilter,
                        sortByScore = (sortOrder == SearchSortOrder.SCORE),
                    )
                } else {
                    client.search(q)
                }
                val latestSort = _searchState.value.sortOrder
                SearchUtils.sortByUserOrder(rawList, expanded.ifEmpty { q }, latestSort)
            }.onSuccess { list ->
                _searchState.update { it.copy(results = list, isLoading = false, error = null) }
            }.onFailure { err ->
                _searchState.update {
                    it.copy(
                        results = emptyList(),
                        isLoading = false,
                        error = err.message ?: "Errore durante la ricerca",
                    )
                }
            }
        }
    }

    fun pickRandomAnime() {
        randomJob?.cancel()
        val previousKey = _randomAnimeState.value.anime?.uniqueKey
        _randomAnimeState.update {
            it.copy(
                isLoading = true,
                error = null,
                showDialog = true,
            )
        }
        randomJob = viewModelScope.launch {
            runCatchingCancellable {
                val excludeKeys = buildSet {
                    history.value.filter { it.isCaughtUp }.forEach { add(it.uniqueKey) }
                    if (previousKey != null) add(previousKey)
                }
                client.getRandomAnime(excludeUniqueKeys = excludeKeys)
            }.onSuccess { picked ->
                _randomAnimeState.value = RandomAnimeUiState(
                    isLoading = false,
                    anime = picked,
                    error = null,
                    showDialog = true,
                )
            }.onFailure { err ->
                _randomAnimeState.update {
                    it.copy(
                        isLoading = false,
                        error = err.message ?: "Impossibile pescare un anime casuale",
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun dismissRandomAnimeDialog() {
        randomJob?.cancel()
        _randomAnimeState.update { it.copy(showDialog = false, isLoading = false, error = null) }
    }

    fun openAnimeDetail(anime: AnimeItem) {
        detailJob?.cancel()
        savedStateHandle[KEY_SAVED_DETAIL_ID] = anime.id
        savedStateHandle[KEY_SAVED_DETAIL_TITLE] = anime.title
        savedStateHandle[KEY_SAVED_DETAIL_DUB] = anime.isDub
        _detailState.value = DetailUiState(anime = anime, episodes = emptyList(), isLoading = true, error = null)

        detailJob = viewModelScope.launch {
            runCatchingCancellable {
                val detailResult = runCatchingCancellable { client.getAnimeDetailsWithRelated(anime.id, anime.isDub) }
                val detailError = detailResult.exceptionOrNull()
                if (detailError is OfflineException) throw detailError
                val resolved = detailResult.getOrNull()
                val fullAnime = resolved?.anime ?: anime
                val related = resolved?.relatedAnimes.orEmpty()
                val eps = client.getEpisodes(fullAnime.id, fullAnime.isDub)
                Triple(fullAnime, related, eps)
            }.onSuccess { (fullAnime, related, eps) ->
                if (favoritesRepo.isFavorite(fullAnime.uniqueKey)) {
                    notificationManager.recordKnownEpisodeCount(fullAnime, eps.size)
                }
                historyRepo.syncKnownEpisodeAvailability(
                    uniqueKey = fullAnime.uniqueKey,
                    availableEpisodes = eps.size,
                    lastEpisodeNum = eps.lastOrNull()?.number,
                )
                val nextSeason = SequelTracker.findNextSeason(fullAnime, related)

                // If user already finished this season and a next season exists, auto-save it once!
                val currentHistory = historyRepo.getEntry(fullAnime.uniqueKey)
                if (currentHistory?.isCaughtUp == true && nextSeason != null) {
                    val prefs = trackedRepo.awaitLoaded()
                    val alreadyKnown = favoritesRepo.isFavorite(nextSeason.uniqueKey) ||
                        historyRepo.getEntry(nextSeason.uniqueKey) != null ||
                        nextSeason.uniqueKey in prefs.discoveredSequelKeys
                    if (!alreadyKnown) {
                        favoritesRepo.toggleFavorite(nextSeason)
                        trackedRepo.setAnimeNotificationEnabled(nextSeason.uniqueKey, enabled = true)
                        trackedRepo.markSequelDiscovered(nextSeason.uniqueKey)
                        notificationManager.recordKnownEpisodeCount(nextSeason, nextSeason.releasedEpisodes)
                    }
                }

                _detailState.value = DetailUiState(
                    anime = fullAnime,
                    episodes = eps,
                    relatedAnimes = related,
                    nextSeason = nextSeason,
                    isLoading = false,
                    error = null,
                )
            }.onFailure { err ->
                val offlineEps = findCompletedOfflineEpisodes(anime.id, anime.isDub)
                if (offlineEps.isNotEmpty()) {
                    _detailState.value = DetailUiState(
                        anime = anime,
                        episodes = offlineEps,
                        isLoading = false,
                        error = null,
                    )
                } else {
                    _detailState.value = DetailUiState(
                        anime = anime,
                        episodes = emptyList(),
                        isLoading = false,
                        error = err.message ?: "Impossibile caricare la scheda anime",
                    )
                }
            }
        }
    }

    private fun findCompletedOfflineEpisodes(animeId: String, isDub: Boolean): List<EpisodeItem> {
        return downloadRepo.downloads.value
            .filter { it.animeId == animeId && it.isDub == isDub && it.status == DownloadStatus.COMPLETED }
            .sortedWith(compareBy { SearchUtils.episodeSortKey(it.episodeNum) })
            .map {
                EpisodeItem(
                    number = it.episodeNum,
                    videoId = it.videoId,
                    language = if (it.isDub) "ita" else "jpn",
                )
            }
    }

    fun switchDetailLanguage(wantDub: Boolean) {
        val current = _detailState.value.anime ?: return
        if (current.isDub == wantDub) return
        val baseTitle = SearchUtils.stripDubTags(current.title)
        val switched = current.copy(
            title = if (wantDub) "$baseTitle (ITA)" else baseTitle,
            isDub = wantDub,
            audioLabel = if (wantDub) "Italiano" else "Giapponese",
        )
        openAnimeDetail(switched)
    }

    fun closeDetail() {
        detailJob?.cancel()
        savedStateHandle.remove<String>(KEY_SAVED_DETAIL_ID)
        savedStateHandle.remove<String>(KEY_SAVED_DETAIL_TITLE)
        savedStateHandle.remove<Boolean>(KEY_SAVED_DETAIL_DUB)
        _detailState.value = DetailUiState()
    }

    fun toggleFavorite(anime: AnimeItem) {
        val wasFav = favoritesRepo.isFavorite(anime.uniqueKey)
        favoritesRepo.toggleFavorite(anime)
        if (!wasFav) {
            val loadedEpCount = if (_detailState.value.anime?.uniqueKey == anime.uniqueKey) {
                _detailState.value.episodes.size
            } else {
                0
            }
            notificationManager.recordKnownEpisodeCount(anime, maxOf(loadedEpCount, anime.releasedEpisodes))
        }
    }

    fun removeFavorite(uniqueKey: String) = favoritesRepo.remove(uniqueKey)
    fun removeHistory(uniqueKey: String) = historyRepo.remove(uniqueKey)
    fun hideFromContinueWatching(uniqueKey: String) = historyRepo.hideFromContinueWatching(uniqueKey)

    fun enqueueDownload(anime: AnimeItem, episode: EpisodeItem) = downloadRepo.enqueueDownload(anime, episode)
    fun retryDownload(entry: DownloadEntry) = downloadRepo.retryDownload(entry)
    fun cancelDownload(key: String) = downloadRepo.cancelDownload(key)
    fun deleteDownload(key: String) = downloadRepo.deleteDownload(key)

    fun startPlayback(
        anime: AnimeItem,
        episodes: List<EpisodeItem>,
        episodeIndex: Int,
        resumeFromHistory: Boolean = true,
    ) {
        if (episodes.isEmpty()) return
        val safeIdx = episodeIndex.coerceIn(0, episodes.lastIndex)
        val ep = episodes[safeIdx]
        val saved = if (resumeFromHistory) {
            historyRepo.getEpisodeProgress(anime.uniqueKey, ep.number)
        } else {
            null
        }
        val startMs = if (saved != null && !saved.completed) saved.positionMs else 0L
        _activePlayer.value = PlayerLaunchRequest(
            anime = anime,
            episodes = episodes,
            startIndex = safeIdx,
            startPositionMs = startMs,
        )
    }

    fun quickWatchAnime(anime: AnimeItem) {
        playbackLaunchJob?.cancel()
        playbackLaunchJob = viewModelScope.launch {
            val fullAnime = runCatchingCancellable { client.getAnimeDetails(anime.id, anime.isDub) }
                .getOrDefault(anime)
            val eps = runCatchingCancellable { client.getEpisodes(fullAnime.id, fullAnime.isDub) }
                .getOrDefault(emptyList())
            val resumeTarget = resolveResumeTarget(eps, historyRepo.getEntry(fullAnime.uniqueKey))
            if (resumeTarget != null) {
                startPlayback(fullAnime, eps, resumeTarget.episodeIndex, resumeFromHistory = true)
            } else {
                openAnimeDetail(fullAnime)
            }
        }
    }

    fun resumeFromHistory(entry: WatchHistoryEntry) {
        playbackLaunchJob?.cancel()
        playbackLaunchJob = viewModelScope.launch {
            val fullAnime = if (entry.anime.malId == 0) {
                runCatchingCancellable { client.getAnimeDetails(entry.anime.id, entry.anime.isDub) }
                    .getOrDefault(entry.anime)
            } else {
                entry.anime
            }
            val loadedEps = runCatchingCancellable { client.getEpisodes(fullAnime.id, fullAnime.isDub) }
                .getOrElse {
                    val offline = findCompletedOfflineEpisodes(fullAnime.id, fullAnime.isDub)
                    offline.ifEmpty {
                        listOf(
                            EpisodeItem(
                                number = entry.currentEpisodeNum,
                                videoId = entry.currentVideoId,
                                language = if (fullAnime.isDub) "ita" else "jpn",
                            )
                        )
                    }
                }
            val target = resolveResumeTarget(loadedEps, entry) ?: return@launch
            _activePlayer.value = PlayerLaunchRequest(
                anime = fullAnime,
                episodes = loadedEps,
                startIndex = target.episodeIndex,
                startPositionMs = target.startPositionMs,
            )
        }
    }

    fun playDownloadedEpisode(entry: DownloadEntry) {
        val anime = historyRepo.getEntry("${entry.animeId}_${if (entry.isDub) "ita" else "sub"}")?.anime
            ?: AnimeItem(
                id = entry.animeId,
                title = entry.animeTitle,
                posterUrl = entry.posterUrl,
                isDub = entry.isDub,
                audioLabel = if (entry.isDub) "Italiano" else "Giapponese",
            )
        val eps = findCompletedOfflineEpisodes(entry.animeId, entry.isDub).ifEmpty {
            listOf(EpisodeItem(number = entry.episodeNum, videoId = entry.videoId))
        }
        val idx = eps.indexOfFirst { it.number == entry.episodeNum }.coerceAtLeast(0)
        startPlayback(anime, eps, idx, resumeFromHistory = true)
    }

    fun closePlayer() {
        _activePlayer.value = null
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSizeBytes.value = withContext(Dispatchers.IO) { computeDirBytes(cacheDir) }
        }
    }

    fun hasNotificationPermission(): Boolean = notificationManager.hasNotificationPermission()

    fun setFavoriteNotificationsEnabled(enabled: Boolean) {
        val interval = settings.value.notifyCheckIntervalHours
        settingsRepo.update { it.copy(notifyFavoriteEpisodes = enabled) }
        notificationManager.syncPeriodicSchedule(enabled = enabled, intervalHours = interval)
        _settingsFeedback.value = if (enabled) {
            "Notifiche anime seguiti attive (controllo automatico ogni ${interval}h + rilevamento nuove stagioni)."
        } else {
            "Notifiche nuovi episodi e nuove stagioni disattivate."
        }
    }

    fun checkFavoriteEpisodesNow() {
        if (_isCheckingFavorites.value) return
        favoriteCheckJob?.cancel()
        favoriteCheckJob = viewModelScope.launch {
            _isCheckingFavorites.value = true
            runCatchingCancellable {
                notificationManager.checkFavoritesForNewEpisodes(
                    respectSettingToggle = false,
                    postNotifications = true,
                )
            }.onSuccess { summary ->
                if (summary.discoveredSequels.isNotEmpty()) {
                    _discoveredSequels.update { existing ->
                        val existingKeys = existing.map { it.newSeason.uniqueKey }.toSet()
                        existing + summary.discoveredSequels.filter { it.newSeason.uniqueKey !in existingKeys }
                    }
                }
                _settingsFeedback.value = when {
                    summary.monitoredCount == 0 && summary.discoveredSequels.isEmpty() ->
                        "Segna almeno un anime con la campanella 🔔 (o nei Preferiti) per ricevere avvisi."
                    summary.episodeAlerts.isNotEmpty() || summary.discoveredSequels.isNotEmpty() -> {
                        val parts = buildList {
                            if (summary.episodeAlerts.isNotEmpty()) {
                                val names = summary.episodeAlerts.take(2)
                                    .joinToString(", ") { "${it.anime.title} (Ep. ${it.episodeLabel})" }
                                val extra = if (summary.episodeAlerts.size > 2) " + altri ${summary.episodeAlerts.size - 2}" else ""
                                add("${summary.episodeAlerts.size} nuovi episodi: $names$extra")
                            }
                            if (summary.discoveredSequels.isNotEmpty()) {
                                val seqNames = summary.discoveredSequels.take(2).joinToString(", ") { it.newSeason.title }
                                add("${summary.discoveredSequels.size} nuove stagioni salvate: $seqNames")
                            }
                        }
                        "Trovati " + parts.joinToString(" • ")
                    }
                    else -> "Nessun nuovo episodio per i tuoi ${summary.monitoredCount} anime monitorati (baseline aggiornata)."
                }
            }.onFailure { err ->
                _settingsFeedback.value = "Errore controllo notifiche: ${err.message ?: "rete non disponibile"}"
            }
            _isCheckingFavorites.value = false
        }
    }

    fun clearSettingsFeedback() {
        _settingsFeedback.value = null
    }

    fun openAnimeInSuiteTv(context: Context, anime: AnimeItem) {
        viewModelScope.launch {
            val enriched = if (anime.anilistId == 0 && anime.malId == 0 && anime.id.isNotBlank()) {
                runCatchingCancellable { client.getAnimeDetails(anime.id, anime.isDub) }
                    .getOrDefault(anime)
            } else {
                anime
            }
            val target = runCatchingCancellable {
                SuiteTvIntegration.resolveSuiteTvTarget(thirdPartyHttpClient, enriched)
            }.getOrNull()
            SuiteTvIntegration.openAnimeInSuiteTv(context, enriched, target)
        }
    }

    fun submitBugReport(
        kind: ReportKind,
        title: String,
        description: String,
        onFinished: (Boolean) -> Unit,
    ) {
        if (_isSubmittingBugReport.value) return
        viewModelScope.launch {
            _isSubmittingBugReport.value = true
            val result = runCatchingCancellable {
                BugReportHelper.submitAnonymousReport(
                    httpClient = thirdPartyHttpClient,
                    kind = kind,
                    title = title,
                    description = description,
                    activeDomain = _homeState.value.activeDomain,
                )
            }.getOrElse {
                BugReportSubmitResult.Unavailable("Errore di rete durante l'invio: usa 'Condividi' o 'Copia'.")
            }
            _isSubmittingBugReport.value = false
            when (result) {
                is BugReportSubmitResult.Success -> {
                    val issueSuffix = if (result.issueNumber > 0) " (#${result.issueNumber})" else ""
                    _settingsFeedback.value = "Segnalazione inviata$issueSuffix! Grazie per il feedback."
                    onFinished(true)
                }
                BugReportSubmitResult.RateLimited -> {
                    _settingsFeedback.value = "Troppe segnalazioni nell'ultima ora. Riprova più tardi."
                    onFinished(false)
                }
                is BugReportSubmitResult.Unavailable -> {
                    _settingsFeedback.value = result.message
                    onFinished(false)
                }
            }
        }
    }

    fun onSettingsEvent(
        event: SettingsEvent,
        onNavigateBack: () -> Unit,
        onLaunchExportBackup: () -> Unit,
        onLaunchImportBackup: () -> Unit,
        onLaunchExportSuiteTv: () -> Unit,
        onLaunchImportSuiteTv: () -> Unit,
        onOpenSuiteTv: () -> Unit,
        onOpenSupportedLinksSettings: () -> Unit,
        onRequestNotificationPermissionAndEnable: () -> Unit,
    ) {
        when (event) {
            SettingsEvent.NavigateBack -> onNavigateBack()
            is SettingsEvent.ChangeThemeMode -> settingsRepo.update { it.copy(themeMode = event.mode) }
            is SettingsEvent.ToggleDynamicColors -> settingsRepo.update { it.copy(dynamicColors = event.enabled) }
            is SettingsEvent.ToggleAutoSkipIntro -> settingsRepo.update { it.copy(autoSkipIntro = event.enabled) }
            is SettingsEvent.ToggleAutoSkipOutro -> settingsRepo.update { it.copy(autoSkipOutro = event.enabled) }
            is SettingsEvent.ToggleAutoPip -> settingsRepo.update { it.copy(autoPipEnabled = event.enabled) }
            is SettingsEvent.ToggleWifiOnlyDownloads -> settingsRepo.update { it.copy(wifiOnlyDownloads = event.enabled) }
            is SettingsEvent.ToggleAutoCheckUpdates -> settingsRepo.update { it.copy(autoCheckUpdates = event.enabled) }
            is SettingsEvent.ToggleFavoriteNotifications -> {
                if (event.enabled && !notificationManager.hasNotificationPermission()) {
                    onRequestNotificationPermissionAndEnable()
                } else {
                    setFavoriteNotificationsEnabled(event.enabled)
                }
            }
            is SettingsEvent.ChangeNotifyIntervalHours -> {
                val safeHours = event.hours.coerceIn(1, 24)
                settingsRepo.update { it.copy(notifyCheckIntervalHours = safeHours) }
                if (settings.value.notifyFavoriteEpisodes) {
                    notificationManager.syncPeriodicSchedule(enabled = true, intervalHours = safeHours)
                }
                _settingsFeedback.value = "Intervallo controllo impostato a ogni ${safeHours}h."
            }
            SettingsEvent.CheckFavoriteEpisodesNow -> checkFavoriteEpisodesNow()
            is SettingsEvent.ToggleSuiteTvQuickAction -> settingsRepo.update { it.copy(suiteTvQuickAction = event.enabled) }
            SettingsEvent.ExportSuiteTvHistory -> onLaunchExportSuiteTv()
            SettingsEvent.ImportSuiteTvHistory -> onLaunchImportSuiteTv()
            SettingsEvent.OpenSuiteTv -> onOpenSuiteTv()
            SettingsEvent.OpenSupportedLinksSettings -> onOpenSupportedLinksSettings()
            SettingsEvent.CheckUpdatesNow -> checkForUpdates(manual = true)
            SettingsEvent.OpenUpdateDialog -> showUpdateDialog()
            SettingsEvent.ExportBackup -> onLaunchExportBackup()
            SettingsEvent.ImportBackup -> onLaunchImportBackup()
            SettingsEvent.ClearCache -> clearAppCache()
            is SettingsEvent.SubmitBugReport -> submitBugReport(
                kind = event.kind,
                title = event.title,
                description = event.description,
                onFinished = event.onDone,
            )
            is SettingsEvent.ShowFeedback -> _settingsFeedback.value = event.message
        }
    }

    fun importSuiteTvHistoryFromUri(contentResolver: ContentResolver, uri: Uri) {
        if (_isImportingSuiteTv.value || _isExportingSuiteTv.value) return
        viewModelScope.launch {
            _isImportingSuiteTv.value = true
            _suiteTvSyncProgress.value = "Lettura file Suite TV..."
            val result = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val rawBytes = readBoundedBytes(contentResolver, uri, MAX_SUITETV_IMPORT_BYTES)
                    val importedRows = SuiteTvIntegration.parseImportedSuiteTvBytes(rawBytes)
                    if (importedRows.isEmpty()) {
                        return@runCatchingCancellable IntArray(5) // [shows, eps, favs, skipped, rateLimited]
                    }

                    val localPool = (favorites.value + history.value.map { it.anime })
                        .associateBy { it.uniqueKey }
                        .values
                        .toList()
                    val userFranchiseProfiles = SequelTracker.buildUserFranchiseProfiles(localPool)

                    // Group all seasons belonging to the same Suite TV show/movie so ARM & AnimeUnity are queried once
                    val grouped = linkedMapOf<String, MutableList<SuiteTvImportedShow>>()
                    for (row in importedRows) {
                        val groupKey = if (row.isMovie) {
                            "m_${row.tvdbId ?: row.tmdbId ?: SearchUtils.normalizeSearchText(row.title)}"
                        } else {
                            "s_${row.tvdbId ?: SearchUtils.normalizeSearchText(SuiteTvIntegration.cleanTitleForSuiteTv(row.title))}"
                        }
                        grouped.getOrPut(groupKey) { mutableListOf() }.add(row)
                    }

                    val groups = grouped.values.take(MAX_SUITETV_IMPORT_GROUPS)
                    val totalGroups = groups.size
                    val skippedNonAnimeOrMissing = AtomicInteger(0)

                    // Phase 1: Fast parallel non-anime pre-filter via ARM v2 (zero calls to AnimeUnity!)
                    val prefilterDone = AtomicInteger(0)
                    val armSemaphore = Semaphore(3)
                    val animeGroupsToSync = coroutineScope {
                        groups.mapIndexed { orderIdx, showGroup ->
                            async {
                                armSemaphore.withPermit {
                                    val first = showGroup.first()
                                    val cleanShowTitle = SuiteTvIntegration.cleanTitleForSuiteTv(first.title)
                                        .ifBlank { first.title.trim() }
                                    val doneIdx = prefilterDone.incrementAndGet()
                                    _suiteTvSyncProgress.value = "Analisi catalogo Suite TV ($doneIdx/$totalGroups)..."

                                    if (cleanShowTitle.length < 2 || first.isHidden) {
                                        skippedNonAnimeOrMissing.incrementAndGet()
                                        return@withPermit null
                                    }

                                    val hasExternalId = (!first.isMovie && first.tvdbId != null) ||
                                        (first.isMovie && first.tmdbId != null)
                                    val armResult = when {
                                        !first.isMovie && first.tvdbId != null -> {
                                            SuiteTvIntegration.lookupArmByTvdbId(thirdPartyHttpClient, first.tvdbId)
                                        }
                                        first.isMovie && first.tmdbId != null -> {
                                            SuiteTvIntegration.lookupArmByTmdbId(thirdPartyHttpClient, first.tmdbId)
                                        }
                                        else -> null
                                    }
                                    // Skip immediately when ARM confirms HTTP 404 (live-action TV show / Western movie)
                                    if (hasExternalId && armResult != null && armResult.confirmedNotAnime) {
                                        skippedNonAnimeOrMissing.incrementAndGet()
                                        return@withPermit null
                                    }
                                    val armMappings = armResult?.mappings.orEmpty()
                                    // If ARM transiently failed (not 404), only proceed if already in local library or favorited
                                    if (hasExternalId && armMappings.isEmpty() && !first.isFavorite) {
                                        val alreadyLocal = localPool.any { cand ->
                                            SearchUtils.normalizeSearchText(SuiteTvIntegration.cleanTitleForSuiteTv(cand.title)) ==
                                                SearchUtils.normalizeSearchText(cleanShowTitle)
                                        }
                                        if (!alreadyLocal) {
                                            skippedNonAnimeOrMissing.incrementAndGet()
                                            return@withPermit null
                                        }
                                    }
                                    Triple(orderIdx, showGroup, armMappings)
                                }
                            }
                        }.awaitAll()
                            .filterNotNull()
                            .sortedBy { it.first }
                    }

                    // Phase 2: Polite sequential AnimeUnity lookup (1 request per anime, throttled, zero getEpisodes(), circuit-breaker on 403/429)
                    val mergedHistory = mutableListOf<WatchHistoryEntry>()
                    val mergedFavorites = mutableListOf<AnimeItem>()
                    val syncedFavoriteKeys = mutableSetOf<String>()
                    var matchedShowsCount = 0
                    var matchedEpisodesCount = 0
                    var remoteCallsMade = 0
                    var rateLimited = false
                    val totalAnime = animeGroupsToSync.size

                    for ((syncIdx, triple) in animeGroupsToSync.withIndex()) {
                        val (_, showGroup, armMappings) = triple
                        val first = showGroup.first()
                        val cleanShowTitle = SuiteTvIntegration.cleanTitleForSuiteTv(first.title)
                            .ifBlank { first.title.trim() }
                        _suiteTvSyncProgress.value = "Importazione anime (${syncIdx + 1}/$totalAnime): $cleanShowTitle"

                        val fKey = SearchUtils.extractFranchiseKey(cleanShowTitle)
                        val prefersDub = userFranchiseProfiles[fKey]?.let { it.prefersDub && !it.prefersSub } ?: false
                        val sortedSeasons = showGroup.sortedBy { it.seasonNumber }
                        val totalWatchedFlat = sortedSeasons.sumOf { it.watchedEpisodes.size }
                        val hideInContinueWatching = first.isStopped

                        // Filter out season 0 specials and standalone MOVIE/OVA/SPECIAL entries for mainline continuous checks
                        val tvArmMappings = armMappings.filter {
                            it.tvdbSeason != 0 &&
                                !it.media.equals("MOVIE", ignoreCase = true) &&
                                !it.media.equals("OVA", ignoreCase = true) &&
                                !it.media.equals("SPECIAL", ignoreCase = true)
                        }
                        // Preserve movies explicitly assigned to a numbered TVDB season (e.g. Initial D Third Stage = S3)
                        val seasonalArmMappings = armMappings.filter {
                            it.tvdbSeason != 0 &&
                                !it.media.equals("OVA", ignoreCase = true) &&
                                !it.media.equals("SPECIAL", ignoreCase = true) &&
                                (!it.media.equals("MOVIE", ignoreCase = true) || (it.tvdbSeason != null && it.tvdbSeason > 0))
                        }
                        val distinctMainlineArmIds = tvArmMappings
                            .mapNotNull { it.anilistId ?: it.malId }
                            .toSet()

                        val cleanNorm = SearchUtils.normalizeSearchText(cleanShowTitle)
                        val localMatches = localPool.filter { cand ->
                            val armIdHit = distinctMainlineArmIds.isNotEmpty() && (
                                (cand.anilistId > 0 && cand.anilistId in distinctMainlineArmIds) ||
                                    (cand.malId > 0 && cand.malId in distinctMainlineArmIds)
                                )
                            armIdHit || SearchUtils.scoreQueryAgainstTitles(
                                cleanShowTitle,
                                listOf(SuiteTvIntegration.cleanTitleForSuiteTv(cand.title), cand.title, cand.titleIt),
                            ) >= 0.80
                        }
                        val localArmMatched = if (distinctMainlineArmIds.isNotEmpty()) {
                            localMatches.filter { cand ->
                                (cand.anilistId > 0 && cand.anilistId in distinctMainlineArmIds) ||
                                    (cand.malId > 0 && cand.malId in distinctMainlineArmIds)
                            }
                        } else {
                            emptyList()
                        }
                        val canUseLocalOnly = if (distinctMainlineArmIds.isNotEmpty()) {
                            val armDistinct = localArmMatched.distinctBy { it.id }
                            val hasMeta = armDistinct.isNotEmpty() && armDistinct.all {
                                !it.displayImageUrl.isNullOrBlank() && maxOf(it.releasedEpisodes, it.totalEpisodes ?: 0) > 0
                            }
                            hasMeta && (
                                first.isMovie ||
                                    sortedSeasons.size <= 1 ||
                                    armDistinct.size >= distinctMainlineArmIds.size ||
                                    (
                                        armDistinct.size == 1 &&
                                            maxOf(armDistinct[0].releasedEpisodes, armDistinct[0].totalEpisodes ?: 0) >= totalWatchedFlat
                                        )
                                )
                        } else {
                            val exactLocal = localMatches.filter { cand ->
                                SearchUtils.normalizeSearchText(SuiteTvIntegration.cleanTitleForSuiteTv(cand.title)) == cleanNorm
                            }.distinctBy { it.id }
                            val hasMeta = exactLocal.isNotEmpty() && exactLocal.all {
                                !it.displayImageUrl.isNullOrBlank() && maxOf(it.releasedEpisodes, it.totalEpisodes ?: 0) > 0
                            }
                            hasMeta && (
                                first.isMovie ||
                                    sortedSeasons.size <= 1 ||
                                    exactLocal.size >= sortedSeasons.size
                                )
                        }

                        val remoteMatches: List<AnimeItem> = if (canUseLocalOnly) {
                            emptyList()
                        } else {
                            if (remoteCallsMade > 0) {
                                delay(450L)
                            }
                            remoteCallsMade++
                            try {
                                client.searchArchiveQuick(cleanShowTitle)
                            } catch (_: RateLimitedException) {
                                rateLimited = true
                                break
                            } catch (_: IOException) {
                                emptyList()
                            }
                        }

                        // Prefer fresh remote metadata (anilistId, malId, posterUrl, releasedEpisodes) over stale local cache
                        val allCandidates = (remoteMatches + localMatches)
                            .distinctBy { it.uniqueKey }
                        if (allCandidates.isEmpty()) {
                            skippedNonAnimeOrMissing.incrementAndGet()
                            continue
                        }

                        var anySeasonMatched = false

                        // Continuous single-entry anime (e.g. Yu-Gi-Oh! Duel Monsters 224 ep, Yu-Gi-Oh! Zexal 146 ep, Great Pretender 23 ep)
                        if (!first.isMovie && sortedSeasons.size > 1 && totalWatchedFlat > 0) {
                            val mainlineCandidates = resolveMainlineCandidates(
                                cleanShowTitle = cleanShowTitle,
                                isMovie = false,
                                allCandidates = allCandidates,
                                prefersDub = prefersDub,
                            )
                            val singleContinuousTarget = when {
                                mainlineCandidates.size == 1 -> mainlineCandidates.first()
                                distinctMainlineArmIds.size <= 1 -> resolveCandidatesForSeason(
                                    seasonNumber = 1,
                                    isMovie = false,
                                    cleanShowTitle = cleanShowTitle,
                                    armMappings = tvArmMappings,
                                    allCandidates = allCandidates,
                                    prefersDub = prefersDub,
                                ).singleOrNull()
                                else -> null
                            }
                            if (singleContinuousTarget != null) {
                                val knownEpCount = singleContinuousTarget.releasedEpisodes.takeIf { it > 0 }
                                    ?: (singleContinuousTarget.totalEpisodes ?: 0)
                                val firstSeasonMax = sortedSeasons.first().watchedEpisodes.maxOrNull() ?: 0
                                val shouldFlatten = knownEpCount >= totalWatchedFlat && (
                                    distinctMainlineArmIds.size <= 1 ||
                                        (mainlineCandidates.size == 1 && knownEpCount > firstSeasonMax)
                                    )
                                if (shouldFlatten) {
                                    val flatTimestamps = sortedSeasons.flatMap { s ->
                                        s.watchedEpisodes.sorted().map { epNum ->
                                            s.episodeTimestampsMs[epNum] ?: s.lastWatchedAtMs
                                        }
                                    }
                                    val entry = buildSyntheticHistoryEntryFromSequentialCount(
                                        anime = singleContinuousTarget,
                                        knownEpCount = knownEpCount,
                                        watchedTimestamps = flatTimestamps,
                                        hiddenFromContinueWatching = hideInContinueWatching,
                                    )
                                    if (entry != null) {
                                        mergedHistory.add(entry)
                                        matchedEpisodesCount += entry.episodesProgress.size
                                        anySeasonMatched = true
                                        if (first.isFavorite || first.isWatchlisted) {
                                            mergedFavorites.add(singleContinuousTarget)
                                            syncedFavoriteKeys.add(singleContinuousTarget.uniqueKey)
                                        }
                                    }
                                }
                            }
                        }

                        // Multi-season / split-cour / single-season / movie mapping (zero getEpisodes() network calls!)
                        if (!anySeasonMatched) {
                            val effectiveArm = if (first.isMovie) armMappings else seasonalArmMappings.ifEmpty { armMappings }
                            for (seasonRow in sortedSeasons) {
                                val matchedCours = resolveCandidatesForSeason(
                                    seasonNumber = seasonRow.seasonNumber,
                                    isMovie = seasonRow.isMovie,
                                    cleanShowTitle = cleanShowTitle,
                                    armMappings = effectiveArm,
                                    allCandidates = allCandidates,
                                    prefersDub = prefersDub,
                                )
                                if (matchedCours.isEmpty()) continue

                                if (seasonRow.watchedEpisodes.isEmpty()) {
                                    if (seasonRow.isFavorite || seasonRow.isWatchlisted) {
                                        mergedFavorites.add(matchedCours.first())
                                        syncedFavoriteKeys.add(matchedCours.first().uniqueKey)
                                        anySeasonMatched = true
                                    }
                                    continue
                                }

                                var epOffset = 0
                                for (courAnime in matchedCours) {
                                    val courReleasedEps = courAnime.releasedEpisodes.takeIf { it > 0 }
                                        ?: (courAnime.totalEpisodes ?: 0)
                                    val effectiveCourSize = if (courReleasedEps > 0) {
                                        courReleasedEps
                                    } else {
                                        seasonRow.watchedEpisodes.maxOrNull() ?: 1
                                    }
                                    val epDurationMs = (courAnime.runtimeMinutes ?: if (seasonRow.isMovie) 90 else 24)
                                        .coerceAtLeast(1) * 60_000L

                                    val epMap = linkedMapOf<String, EpisodeProgress>()
                                    var highestEpInt = 0
                                    var latestTs = seasonRow.lastWatchedAtMs

                                    for (epInt in 1..effectiveCourSize) {
                                        val targetSeasonEp = epOffset + epInt
                                        val matchedNum = when {
                                            targetSeasonEp in seasonRow.watchedEpisodes -> targetSeasonEp
                                            matchedCours.size == 1 && epInt in seasonRow.watchedEpisodes -> epInt
                                            else -> null
                                        } ?: continue

                                        val ts = seasonRow.episodeTimestampsMs[matchedNum] ?: seasonRow.lastWatchedAtMs
                                        epMap[epInt.toString()] = EpisodeProgress(
                                            episodeNum = epInt.toString(),
                                            videoId = "",
                                            positionMs = 0L,
                                            durationMs = epDurationMs,
                                            completed = true,
                                            updatedAt = ts,
                                        )
                                        highestEpInt = epInt
                                        if (ts > latestTs) latestTs = ts
                                    }

                                    epOffset += effectiveCourSize
                                    if (epMap.isNotEmpty() && highestEpInt > 0) {
                                        val atLatest = courReleasedEps > 0 && highestEpInt >= courReleasedEps
                                        mergedHistory.add(
                                            WatchHistoryEntry(
                                                anime = courAnime.copy(
                                                    releasedEpisodes = maxOf(courAnime.releasedEpisodes, courReleasedEps),
                                                ),
                                                currentEpisodeNum = highestEpInt.toString(),
                                                currentVideoId = "",
                                                progressMs = 0L,
                                                durationMs = epDurationMs,
                                                completed = true,
                                                isAtLatestEpisode = atLatest,
                                                hiddenFromContinueWatching = hideInContinueWatching,
                                                updatedAt = latestTs,
                                                episodesProgress = epMap,
                                            )
                                        )
                                        matchedEpisodesCount += epMap.size
                                        anySeasonMatched = true
                                        if (seasonRow.isFavorite || seasonRow.isWatchlisted) {
                                            mergedFavorites.add(courAnime)
                                            syncedFavoriteKeys.add(courAnime.uniqueKey)
                                        }
                                    }
                                }
                            }
                        }

                        if (anySeasonMatched) {
                            matchedShowsCount++
                        } else {
                            skippedNonAnimeOrMissing.incrementAndGet()
                        }

                        // Incremental save every 15 anime so progress is never lost
                        if (mergedHistory.size >= 15) {
                            historyRepo.mergeImport(mergedHistory.toList())
                            mergedHistory.clear()
                        }
                        if (mergedFavorites.size >= 15) {
                            favoritesRepo.mergeImport(mergedFavorites.distinctBy { it.uniqueKey })
                            mergedFavorites.clear()
                        }
                    }

                    if (mergedHistory.isNotEmpty()) {
                        historyRepo.mergeImport(mergedHistory.toList())
                    }
                    if (mergedFavorites.isNotEmpty()) {
                        favoritesRepo.mergeImport(mergedFavorites.distinctBy { it.uniqueKey })
                    }
                    intArrayOf(
                        matchedShowsCount,
                        matchedEpisodesCount,
                        syncedFavoriteKeys.size,
                        skippedNonAnimeOrMissing.get(),
                        if (rateLimited) 1 else 0,
                    )
                }
            }
            _isImportingSuiteTv.value = false
            _suiteTvSyncProgress.value = null
            _settingsFeedback.value = result.fold(
                onSuccess = { counts ->
                    val shows = counts[0]
                    val eps = counts[1]
                    val favs = counts[2]
                    val skipped = counts[3]
                    val wasRateLimited = counts[4] == 1
                    when {
                        wasRateLimited && shows > 0 ->
                            "Importati $shows anime ($eps episodi). AnimeUnity ha limitato temporaneamente le richieste: attendi 1-2 minuti e riprova per completare i restanti."
                        wasRateLimited ->
                            "AnimeUnity sta limitando temporaneamente le richieste: attendi 1-2 minuti e riprova l'importazione."
                        shows == 0 && favs == 0 && skipped == 0 ->
                            "Nessun episodio trovato nel file (supportati suite-export .zip, .ndjson e Trakt .json)."
                        shows == 0 && favs == 0 ->
                            "Nessun anime corrispondente trovato su AnimeUnity ($skipped serie/film non-anime ignorati)."
                        skipped > 0 ->
                            "Importati $shows anime ($eps episodi) da Suite TV! ($skipped titoli non-anime/non trovati ignorati)"
                        else ->
                            "Importati $shows anime ($eps episodi) da Suite TV!"
                    }
                },
                onFailure = { "File Suite TV non valido: ${it.message}" },
            )
        }
    }

    private fun buildSyntheticHistoryEntryFromSequentialCount(
        anime: AnimeItem,
        knownEpCount: Int,
        watchedTimestamps: List<Long>,
        hiddenFromContinueWatching: Boolean = false,
    ): WatchHistoryEntry? {
        if (watchedTimestamps.isEmpty()) return null
        val totalAvailable = knownEpCount.takeIf { it > 0 }
            ?: anime.releasedEpisodes.takeIf { it > 0 }
            ?: (anime.totalEpisodes ?: 0)
        val count = if (totalAvailable > 0) minOf(watchedTimestamps.size, totalAvailable) else watchedTimestamps.size
        if (count <= 0) return null

        val epDurationMs = (anime.runtimeMinutes ?: 24).coerceAtLeast(1) * 60_000L
        val epMap = linkedMapOf<String, EpisodeProgress>()
        var maxTs = 0L
        for (i in 0 until count) {
            val epNumStr = (i + 1).toString()
            val ts = watchedTimestamps[i]
            epMap[epNumStr] = EpisodeProgress(
                episodeNum = epNumStr,
                videoId = "",
                positionMs = 0L,
                durationMs = epDurationMs,
                completed = true,
                updatedAt = ts,
            )
            if (ts > maxTs) maxTs = ts
        }
        val atLatest = totalAvailable > 0 && count >= totalAvailable
        return WatchHistoryEntry(
            anime = anime.copy(releasedEpisodes = maxOf(anime.releasedEpisodes, totalAvailable)),
            currentEpisodeNum = count.toString(),
            currentVideoId = "",
            progressMs = 0L,
            durationMs = epDurationMs,
            completed = true,
            isAtLatestEpisode = atLatest,
            hiddenFromContinueWatching = hiddenFromContinueWatching,
            updatedAt = maxTs,
            episodesProgress = epMap,
        )
    }

    private fun resolveMainlineCandidates(
        cleanShowTitle: String,
        isMovie: Boolean,
        allCandidates: List<AnimeItem>,
        prefersDub: Boolean,
    ): List<AnimeItem> {
        val strongTitle = allCandidates.filter { cand ->
            if (isMovie && !cand.type.equals("Movie", ignoreCase = true)) return@filter false
            SearchUtils.scoreQueryAgainstTitles(
                cleanShowTitle,
                listOf(SuiteTvIntegration.cleanTitleForSuiteTv(cand.title), cand.title, cand.titleIt, cand.titleJa),
            ) >= 0.78
        }
        if (strongTitle.isEmpty()) return emptyList()
        val byId = LinkedHashMap<String, AnimeItem>()
        for (item in strongTitle) {
            val existing = byId[item.id]
            if (existing == null || (item.isDub == prefersDub && existing.isDub != prefersDub)) {
                byId[item.id] = item
            }
        }
        val deduped = SearchUtils.sortAnimeItems(byId.values.toList())
        if (isMovie) return deduped
        return deduped.filter {
            it.type.equals("TV", ignoreCase = true) || it.type.equals("ONA", ignoreCase = true)
        }.ifEmpty { deduped }
    }

    private fun resolveCandidatesForSeason(
        seasonNumber: Int,
        isMovie: Boolean,
        cleanShowTitle: String,
        armMappings: List<ArmSeasonMapping>,
        allCandidates: List<AnimeItem>,
        prefersDub: Boolean,
    ): List<AnimeItem> {
        fun preferLanguageVariant(items: List<AnimeItem>): List<AnimeItem> {
            val byId = LinkedHashMap<String, AnimeItem>()
            for (item in items) {
                val existing = byId[item.id]
                if (existing == null || (item.isDub == prefersDub && existing.isDub != prefersDub)) {
                    byId[item.id] = item
                }
            }
            return SearchUtils.sortAnimeItems(byId.values.toList())
        }

        // 1. Exact AniList / MAL ID matching from ARM for this TVDB season (when candidates carry IDs)
        val seasonArm = if (isMovie) {
            armMappings
        } else {
            armMappings.filter { it.tvdbSeason == seasonNumber }
                .ifEmpty { armMappings.filter { it.tvdbSeason == null } }
        }
        if (seasonArm.isNotEmpty()) {
            val anilistIds = seasonArm.mapNotNull { it.anilistId }.toSet()
            val malIds = seasonArm.mapNotNull { it.malId }.toSet()
            val idMatched = allCandidates.filter { cand ->
                (cand.anilistId > 0 && cand.anilistId in anilistIds) ||
                    (cand.malId > 0 && cand.malId in malIds)
            }
            if (idMatched.isNotEmpty()) {
                return preferLanguageVariant(idMatched)
            }
        }

        // 2. High-confidence title & ARM multi-cour chronological mapping
        val mainline = resolveMainlineCandidates(cleanShowTitle, isMovie, allCandidates, prefersDub)
        if (mainline.isEmpty()) return emptyList()
        if (isMovie) return listOf(mainline.first())

        val armSeasonCounts = armMappings
            .filter {
                val s = it.tvdbSeason
                s != null && s > 0 &&
                    !it.media.equals("MOVIE", ignoreCase = true) &&
                    !it.media.equals("OVA", ignoreCase = true) &&
                    !it.media.equals("SPECIAL", ignoreCase = true)
            }
            .groupBy { it.tvdbSeason!! }
            .mapValues { (_, list) ->
                list.mapNotNull { it.anilistId ?: it.malId }.distinct().size.coerceAtLeast(1)
            }

        val seasonCourCount = armSeasonCounts[seasonNumber]
        if (seasonCourCount != null) {
            val offsetBefore = armSeasonCounts.filterKeys { it in 1 until seasonNumber }.values.sum()
            if (seasonCourCount > 1 && offsetBefore + seasonCourCount <= mainline.size) {
                return mainline.subList(offsetBefore, offsetBefore + seasonCourCount)
            }
            if (seasonCourCount == 1 && offsetBefore != seasonNumber - 1 && offsetBefore in mainline.indices) {
                return listOf(mainline[offsetBefore])
            }
        }

        val explicit = mainline.firstOrNull { SuiteTvIntegration.extractSeasonNumber(it.title) == seasonNumber }
        if (explicit != null) return listOf(explicit)
        val idx = (seasonNumber - 1).coerceIn(0, mainline.lastIndex)
        return listOf(mainline[idx])
    }

    fun exportSuiteTvHistoryToUri(contentResolver: ContentResolver, uri: Uri) {
        if (_isExportingSuiteTv.value || _isImportingSuiteTv.value) return
        viewModelScope.launch {
            _isExportingSuiteTv.value = true
            _suiteTvSyncProgress.value = "Preparazione export Suite TV (.zip)..."
            val result = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val histList = history.value
                    val favList = favorites.value
                    val allAnimeByKey = LinkedHashMap<String, AnimeItem>()
                    for (h in histList) allAnimeByKey[h.uniqueKey] = h.anime
                    for (f in favList) allAnimeByKey.putIfAbsent(f.uniqueKey, f)

                    // Phase 1: Sequentially enrich any anime missing AniList/MAL IDs with polite pacing
                    val enrichedByKey = LinkedHashMap<String, AnimeItem>(allAnimeByKey.size)
                    var animeUnityCalls = 0
                    var exportRateLimited = false
                    val entriesList = allAnimeByKey.entries.toList()
                    for ((idx, entry) in entriesList.withIndex()) {
                        val (key, anime) = entry
                        if (!exportRateLimited && anime.anilistId == 0 && anime.malId == 0 && anime.id.isNotBlank()) {
                            _suiteTvSyncProgress.value = "Recupero metadati (${idx + 1}/${entriesList.size})..."
                            if (animeUnityCalls > 0) delay(350L)
                            animeUnityCalls++
                            val enriched = try {
                                client.getAnimeDetails(anime.id, anime.isDub)
                            } catch (_: RateLimitedException) {
                                exportRateLimited = true
                                anime
                            } catch (_: IOException) {
                                anime
                            }
                            enrichedByKey[key] = enriched
                        } else {
                            enrichedByKey[key] = anime
                        }
                    }

                    // Phase 2: Parallel Suite TV ID resolution via third-party ARM API
                    val resolvedTargets = ConcurrentHashMap<String, ResolvedSuiteTvTarget>()
                    val total = enrichedByKey.size
                    val done = AtomicInteger(0)
                    val semaphore = Semaphore(4)

                    coroutineScope {
                        enrichedByKey.entries.map { (key, enriched) ->
                            async {
                                semaphore.withPermit {
                                    val idx = done.incrementAndGet()
                                    _suiteTvSyncProgress.value = "Mappatura Suite TV ($idx/$total)..."
                                    val target = runCatchingCancellable {
                                        SuiteTvIntegration.resolveSuiteTvTarget(thirdPartyHttpClient, enriched)
                                    }.getOrNull()
                                    if (target != null) {
                                        resolvedTargets[key] = target
                                    }
                                }
                            }
                        }.awaitAll()
                    }

                    val export = SuiteTvIntegration.buildNativeSuiteTvZip(
                        history = histList,
                        favorites = favList,
                        resolvedTargetsByKey = resolvedTargets,
                        appVersion = BuildConfig.VERSION_NAME,
                        appBuild = BuildConfig.VERSION_CODE,
                    )
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(export.zipBytes)
                    } ?: error("Impossibile scrivere sul file selezionato")
                    export
                }
            }
            _isExportingSuiteTv.value = false
            _suiteTvSyncProgress.value = null
            _settingsFeedback.value = result.fold(
                onSuccess = { exp ->
                    if (exp.showCount == 0 && exp.movieCount == 0) {
                        "Archivio Suite TV creato, ma non risultano ancora anime mappati nella libreria."
                    } else {
                        "Esportati ${exp.showCount} anime (${exp.episodeCount} episodi) nel formato nativo Suite TV (.zip)!"
                    }
                },
                onFailure = { "Errore esportazione Suite TV: ${it.message}" },
            )
        }
    }

    fun exportBackupToUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val payload = buildBackupJson()
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("Impossibile scrivere sul file selezionato")
                }
            }
            _settingsFeedback.value = result.fold(
                onSuccess = { "Backup esportato con successo!" },
                onFailure = { "Errore esportazione backup: ${it.message}" },
            )
        }
    }

    fun importBackupFromUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val rawText = readBoundedUtf8(contentResolver, uri, MAX_BACKUP_IMPORT_BYTES)
                    importBackupJson(rawText).getOrThrow()
                }
            }
            _settingsFeedback.value = result.fold(
                onSuccess = { it },
                onFailure = { "File backup non valido: ${it.message}" },
            )
        }
    }

    private fun readBoundedUtf8(contentResolver: ContentResolver, uri: Uri, maxBytes: Int): String =
        readBoundedBytes(contentResolver, uri, maxBytes).toString(Charsets.UTF_8)

    private fun readBoundedBytes(contentResolver: ContentResolver, uri: Uri, maxBytes: Int): ByteArray {
        val stream = contentResolver.openInputStream(uri)
            ?: throw IOException("Impossibile leggere il file selezionato")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buf)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    throw IOException("File troppo grande (limite massimo ${maxBytes / (1024 * 1024)} MB)")
                }
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }
    }

    fun buildBackupJson(): String {
        val data = AppBackupData(
            schemaVersion = 2,
            exportedAt = System.currentTimeMillis(),
            settings = settings.value,
            history = history.value,
            favorites = favorites.value,
            trackedNotifications = trackedNotificationPrefs.value,
        )
        return backupJson.encodeToString(data)
    }

    fun importBackupJson(rawText: String): Result<String> {
        return runCatching {
            val parsed = backupJson.decodeFromString<AppBackupData>(rawText)
            historyRepo.mergeImport(parsed.history)
            favoritesRepo.mergeImport(parsed.favorites)
            trackedRepo.mergeImport(parsed.trackedNotifications)
            settingsRepo.replace(parsed.settings)
            notificationManager.syncPeriodicSchedule(
                enabled = parsed.settings.notifyFavoriteEpisodes,
                intervalHours = parsed.settings.notifyCheckIntervalHours,
            )
            _searchState.update { it.copy(recentQueries = parsed.settings.recentQueries) }
            "Ripristino completato: ${parsed.history.size} cronologia, ${parsed.favorites.size} preferiti."
        }
    }

    private fun clearAppCache() {
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                deleteDirContent(cacheDir)
                computeDirBytes(cacheDir)
            }
            _cacheSizeBytes.value = remaining
            _settingsFeedback.value = "Cache svuotata con successo."
        }
    }

    private fun computeDirBytes(dir: File): Long {
        var total = 0L
        dir.listFiles()?.forEach { f ->
            total += if (f.isDirectory) computeDirBytes(f) else f.length()
        }
        return total
    }

    private fun deleteDirContent(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                deleteDirContent(f)
                f.delete()
            } else {
                f.delete()
            }
        }
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (_updateState.value.isChecking || _updateState.value.isDownloading) return
        updateJob?.cancel()
        _updateState.update {
            it.copy(
                isChecking = true,
                statusMessage = if (manual) "Controllo aggiornamenti su GitHub..." else it.statusMessage,
            )
        }
        updateJob = viewModelScope.launch {
            runCatchingCancellable { updater.checkForUpdate() }
                .onSuccess { info ->
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            availableUpdate = info,
                            showDialog = info != null,
                            statusMessage = when {
                                info != null -> "Nuova versione ${info.tagName} disponibile!"
                                manual -> "L'app è già aggiornata all'ultima versione."
                                else -> null
                            },
                        )
                    }
                }
                .onFailure { err ->
                    _updateState.update {
                        it.copy(
                            isChecking = false,
                            statusMessage = if (manual) {
                                "Impossibile verificare aggiornamenti (${err.message ?: "rete non disponibile"})."
                            } else {
                                null
                            },
                        )
                    }
                }
        }
    }

    fun showUpdateDialog() {
        if (_updateState.value.availableUpdate != null) {
            _updateState.update { it.copy(showDialog = true) }
        }
    }

    fun dismissUpdateDialog() {
        if (!_updateState.value.isDownloading) {
            _updateState.update { it.copy(showDialog = false, needsInstallPermission = false) }
        }
    }

    fun downloadAndInstallUpdate() {
        val info = _updateState.value.availableUpdate ?: return
        if (_updateState.value.isDownloading) return

        val existingApk = _updateState.value.pendingApkFile
        if (existingApk != null && existingApk.exists()) {
            if (updater.canInstallPackages()) {
                _updateState.update { it.copy(needsInstallPermission = false) }
                updater.launchApkInstaller(existingApk)
            } else {
                updater.openInstallPermissionSettings()
            }
            return
        }

        updateJob = viewModelScope.launch {
            _updateState.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = info.sizeBytes,
                    statusMessage = "Download e verifica SHA-256 ${info.tagName}...",
                    needsInstallPermission = false,
                )
            }
            runCatchingCancellable {
                updater.downloadUpdateApk(info) { frac, dl, tot ->
                    _updateState.update {
                        it.copy(
                            downloadProgress = frac,
                            downloadedBytes = dl,
                            totalBytes = tot,
                        )
                    }
                }
            }.onSuccess { apkFile ->
                if (updater.canInstallPackages()) {
                    _updateState.update {
                        it.copy(
                            isDownloading = false,
                            pendingApkFile = apkFile,
                            statusMessage = "Firma & SHA-256 verificati. Avvio installazione...",
                        )
                    }
                    updater.launchApkInstaller(apkFile)
                } else {
                    _updateState.update {
                        it.copy(
                            isDownloading = false,
                            pendingApkFile = apkFile,
                            needsInstallPermission = true,
                            statusMessage = "Abilita 'Consenti da questa sorgente' per completare l'aggiornamento.",
                        )
                    }
                    updater.openInstallPermissionSettings()
                }
            }.onFailure { err ->
                _updateState.update {
                    it.copy(
                        isDownloading = false,
                        statusMessage = "Aggiornamento bloccato/fallito: ${err.message}",
                    )
                }
            }
        }
    }
}
