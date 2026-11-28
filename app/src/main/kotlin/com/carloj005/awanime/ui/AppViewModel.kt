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
import com.carloj005.awanime.data.integrations.AnimeUnityDeepLinkParser
import com.carloj005.awanime.data.integrations.BugReportHelper
import com.carloj005.awanime.data.integrations.BugReportSubmitResult
import com.carloj005.awanime.data.integrations.ReportKind
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

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
        private const val MAX_SUITETV_IMPORT_SHOWS = 60

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
        if (_isImportingSuiteTv.value) return
        viewModelScope.launch {
            _isImportingSuiteTv.value = true
            val result = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val rawText = readBoundedUtf8(contentResolver, uri, MAX_BACKUP_IMPORT_BYTES)
                    val importedShows = SuiteTvIntegration.parseImportedSuiteTvJson(rawText)
                    if (importedShows.isEmpty()) {
                        return@runCatchingCancellable Triple(0, 0, 0)
                    }

                    val localPool = (favorites.value + history.value.map { it.anime })
                        .associateBy { it.uniqueKey }
                        .values
                        .toList()
                    val toMerge = mutableListOf<WatchHistoryEntry>()
                    var matchedShows = 0
                    var matchedEpisodes = 0
                    var skippedShows = 0

                    for (show in importedShows.take(MAX_SUITETV_IMPORT_SHOWS)) {
                        val targetNorm = SearchUtils.normalizeSearchText(SuiteTvIntegration.cleanTitleForSuiteTv(show.title))
                        if (targetNorm.length < 2) {
                            skippedShows++
                            continue
                        }

                        val matchedAnime = findMatchingAnimeForSuiteTvShow(
                            showTitle = show.title,
                            seasonNumber = show.seasonNumber,
                            localPool = localPool,
                        )
                        if (matchedAnime == null) {
                            skippedShows++
                            continue
                        }

                        val episodes = runCatchingCancellable {
                            client.getEpisodes(matchedAnime.id, matchedAnime.isDub)
                        }.getOrDefault(emptyList())
                        if (episodes.isEmpty()) {
                            skippedShows++
                            continue
                        }

                        val epMap = linkedMapOf<String, EpisodeProgress>()
                        var latestWatchedEp: EpisodeItem? = null
                        for (ep in episodes) {
                            val epInt = ep.number.trim().split(".", "-").firstOrNull()?.toIntOrNull() ?: continue
                            if (epInt in show.watchedEpisodes) {
                                epMap[ep.number] = EpisodeProgress(
                                    episodeNum = ep.number,
                                    videoId = ep.videoId,
                                    positionMs = 0L,
                                    durationMs = ep.runtimeSeconds.toLong().coerceAtLeast(1L) * 1000L,
                                    completed = true,
                                    updatedAt = show.lastWatchedAtMs,
                                )
                                latestWatchedEp = ep
                            }
                        }
                        if (epMap.isEmpty() || latestWatchedEp == null) {
                            skippedShows++
                            continue
                        }

                        val atLatest = episodes.lastOrNull()?.number == latestWatchedEp.number
                        toMerge.add(
                            WatchHistoryEntry(
                                anime = matchedAnime.copy(releasedEpisodes = maxOf(matchedAnime.releasedEpisodes, episodes.size)),
                                currentEpisodeNum = latestWatchedEp.number,
                                currentVideoId = latestWatchedEp.videoId,
                                progressMs = 0L,
                                durationMs = latestWatchedEp.runtimeSeconds.toLong().coerceAtLeast(1L) * 1000L,
                                completed = true,
                                isAtLatestEpisode = atLatest,
                                updatedAt = show.lastWatchedAtMs,
                                episodesProgress = epMap,
                            )
                        )
                        matchedShows++
                        matchedEpisodes += epMap.size
                    }

                    if (toMerge.isNotEmpty()) {
                        historyRepo.mergeImport(toMerge)
                    }
                    Triple(matchedShows, matchedEpisodes, skippedShows)
                }
            }
            _isImportingSuiteTv.value = false
            _settingsFeedback.value = result.fold(
                onSuccess = { (shows, eps, skipped) ->
                    when {
                        shows == 0 && skipped == 0 ->
                            "Nessun episodio visto trovato nel JSON selezionato (supportati Trakt JSON e Suite TV / TV Time JSON)."
                        shows == 0 ->
                            "Nessun anime corrispondente trovato su AnimeUnity ($skipped serie/film ignorati)."
                        skipped > 0 ->
                            "Importati $shows anime ($eps episodi) da Suite TV! ($skipped titoli non presenti su AnimeUnity ignorati)"
                        else ->
                            "Importati $shows anime ($eps episodi) da Suite TV nella cronologia!"
                    }
                },
                onFailure = { "File JSON Suite TV non valido: ${it.message}" },
            )
        }
    }

    private suspend fun findMatchingAnimeForSuiteTvShow(
        showTitle: String,
        seasonNumber: Int,
        localPool: List<AnimeItem>,
    ): AnimeItem? {
        val cleanShow = SuiteTvIntegration.cleanTitleForSuiteTv(showTitle).ifBlank { showTitle.trim() }
        val localMatches = localPool.filter { candidate ->
            SearchUtils.scoreQueryAgainstTitles(
                cleanShow,
                listOf(SuiteTvIntegration.cleanTitleForSuiteTv(candidate.title), candidate.title, candidate.titleIt),
            ) >= 0.84
        }
        if (localMatches.isNotEmpty()) {
            return pickSeasonCandidate(localMatches, seasonNumber)
        }

        val remoteResults = runCatchingCancellable { client.search(cleanShow) }.getOrDefault(emptyList())
        if (remoteResults.isEmpty()) return null

        val strongMatches = remoteResults.filter { candidate ->
            SearchUtils.scoreQueryAgainstTitles(
                cleanShow,
                listOf(SuiteTvIntegration.cleanTitleForSuiteTv(candidate.title), candidate.title, candidate.titleIt, candidate.titleJa),
            ) >= 0.78
        }
        if (strongMatches.isEmpty()) return null
        return pickSeasonCandidate(strongMatches, seasonNumber)
    }

    private fun pickSeasonCandidate(candidates: List<AnimeItem>, seasonNumber: Int): AnimeItem {
        val preferredSub = candidates.filter { !it.isDub }.ifEmpty { candidates }
        val mainline = preferredSub.filter {
            it.type.equals("TV", ignoreCase = true) || it.type.equals("ONA", ignoreCase = true)
        }.ifEmpty { preferredSub }
        val ordered = SearchUtils.sortAnimeItems(mainline)
        val explicit = ordered.firstOrNull { SuiteTvIntegration.extractSeasonNumber(it.title) == seasonNumber }
        if (explicit != null) return explicit
        val idx = (seasonNumber - 1).coerceIn(0, ordered.lastIndex)
        return ordered[idx]
    }

    fun exportSuiteTvHistoryToUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val export = SuiteTvIntegration.buildTraktHistoryExport(history.value)
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(export.jsonPayload.toByteArray(Charsets.UTF_8))
                    } ?: error("Impossibile scrivere sul file selezionato")
                    export
                }
            }
            _settingsFeedback.value = result.fold(
                onSuccess = { exp ->
                    if (exp.episodeCount == 0) {
                        "File Suite TV creato, ma non risultano ancora episodi completati nella cronologia."
                    } else {
                        "Esportati ${exp.episodeCount} episodi (${exp.showCount} anime) per Suite TV! " +
                            "Apri Suite TV → Impostazioni → Importa → Trakt e seleziona questo file."
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

    private fun readBoundedUtf8(contentResolver: ContentResolver, uri: Uri, maxBytes: Int): String {
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
            return out.toString(Charsets.UTF_8.name())
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
