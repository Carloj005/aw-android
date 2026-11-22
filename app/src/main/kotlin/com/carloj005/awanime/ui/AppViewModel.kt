package com.carloj005.awanime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.ThemeMode
import com.carloj005.awanime.data.model.UpdateInfo
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.network.SearchUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class HomeUiState(
    val selectedFilter: LatestFilter = LatestFilter.ALL,
    val heroCarousel: List<AnimeItem> = emptyList(),
    val items: List<AnimeItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val activeDomain: String = "animeunity.cx",
)

data class SearchUiState(
    val query: String = "",
    val results: List<AnimeItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val recentQueries: List<String> = emptyList(),
)

data class DetailUiState(
    val anime: AnimeItem? = null,
    val episodes: List<EpisodeItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
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

class AppViewModel : ViewModel() {

    private val client = AwAnimeApp.instance.animeUnityClient
    val historyRepo = AwAnimeApp.instance.historyRepository
    val downloadRepo = AwAnimeApp.instance.downloadRepository
    val settingsRepo = AwAnimeApp.instance.settingsRepository
    val updater = AwAnimeApp.instance.appUpdater

    val history: StateFlow<List<WatchHistoryEntry>> = historyRepo.history
    val downloads: StateFlow<List<DownloadEntry>> = downloadRepo.downloads
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val homeCache = mutableMapOf<LatestFilter, List<AnimeItem>>()
    private var homeJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var updateJob: Job? = null

    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _detailState = MutableStateFlow(DetailUiState())
    val detailState: StateFlow<DetailUiState> = _detailState.asStateFlow()

    private val _activePlayer = MutableStateFlow<PlayerLaunchRequest?>(null)
    val activePlayer: StateFlow<PlayerLaunchRequest?> = _activePlayer.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    init {
        loadHome(LatestFilter.ALL)
        if (settings.value.autoCheckUpdates) {
            checkForUpdates(manual = false)
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
                    error = null,
                    activeDomain = client.baseUrl.removePrefix("https://"),
                )
            }
            return
        }

        _homeState.update {
            it.copy(
                selectedFilter = filter,
                isLoading = true,
                error = null,
            )
        }
        homeJob = viewModelScope.launch {
            runCatching {
                val list = client.getLatestEpisodes(filter)
                val carousel = runCatching { client.getHomeCarousel(forceRefresh = forceRefresh) }.getOrDefault(emptyList())
                list to carousel
            }.onSuccess { (list, carousel) ->
                homeCache[filter] = list
                _homeState.update { st ->
                    st.copy(
                        selectedFilter = filter,
                        heroCarousel = if (carousel.isNotEmpty()) carousel else st.heroCarousel,
                        items = list,
                        isLoading = false,
                        error = null,
                        activeDomain = client.baseUrl.removePrefix("https://"),
                    )
                }
            }.onFailure { err ->
                _homeState.update {
                    it.copy(
                        selectedFilter = filter,
                        isLoading = false,
                        error = err.message ?: "Errore di connessione ad AnimeUnity",
                    )
                }
            }
        }
    }

    fun updateSearchQuery(newQuery: String) {
        _searchState.update { it.copy(query = newQuery) }
    }

    fun performSearch(rawQuery: String = _searchState.value.query) {
        val q = rawQuery.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        _searchState.update { st ->
            val updatedRecent = (listOf(q) + st.recentQueries.filterNot { it.equals(q, ignoreCase = true) }).take(8)
            st.copy(
                query = q,
                isLoading = true,
                hasSearched = true,
                error = null,
                recentQueries = updatedRecent,
            )
        }
        searchJob = viewModelScope.launch {
            runCatching { client.search(q) }
                .onSuccess { list ->
                    _searchState.update {
                        it.copy(results = list, isLoading = false, error = null)
                    }
                }
                .onFailure { err ->
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

    fun openAnimeDetail(anime: AnimeItem) {
        detailJob?.cancel()
        _detailState.value = DetailUiState(anime = anime, episodes = emptyList(), isLoading = true, error = null)
        detailJob = viewModelScope.launch {
            runCatching {
                val fullAnime = runCatching { client.getAnimeDetails(anime.id, anime.isDub) }
                    .getOrDefault(anime)
                val eps = client.getEpisodes(fullAnime.id, fullAnime.isDub)
                fullAnime to eps
            }.onSuccess { (fullAnime, eps) ->
                _detailState.value = DetailUiState(
                    anime = fullAnime,
                    episodes = eps,
                    isLoading = false,
                    error = null,
                )
            }.onFailure { err ->
                val offlineEps = downloadRepo.downloads.value
                    .filter { it.animeId == anime.id && it.isDub == anime.isDub && it.status == DownloadStatus.COMPLETED }
                    .map {
                        EpisodeItem(
                            number = it.episodeNum,
                            videoId = it.videoId,
                            language = if (it.isDub) "ita" else "jpn",
                        )
                    }
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
        _detailState.value = DetailUiState()
    }

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
        viewModelScope.launch {
            val fullAnime = runCatching { client.getAnimeDetails(anime.id, anime.isDub) }.getOrDefault(anime)
            val eps = runCatching { client.getEpisodes(fullAnime.id, fullAnime.isDub) }.getOrDefault(emptyList())
            if (eps.isNotEmpty()) {
                val hist = historyRepo.getEntry(fullAnime.uniqueKey)
                val targetIdx = if (hist != null) {
                    val idx = eps.indexOfFirst { it.number == hist.currentEpisodeNum }.coerceAtLeast(0)
                    if (hist.completed && idx + 1 < eps.size) idx + 1 else idx
                } else {
                    0
                }
                startPlayback(fullAnime, eps, targetIdx, resumeFromHistory = true)
            } else {
                openAnimeDetail(fullAnime)
            }
        }
    }

    fun resumeFromHistory(entry: WatchHistoryEntry) {
        viewModelScope.launch {
            val fullAnime = if (entry.anime.malId == 0) {
                runCatching { client.getAnimeDetails(entry.anime.id, entry.anime.isDub) }.getOrDefault(entry.anime)
            } else {
                entry.anime
            }
            val loadedEps = runCatching { client.getEpisodes(fullAnime.id, fullAnime.isDub) }
                .getOrElse {
                    listOf(
                        EpisodeItem(
                            number = entry.currentEpisodeNum,
                            videoId = entry.currentVideoId,
                            language = if (fullAnime.isDub) "ita" else "jpn",
                        )
                    )
                }
            if (loadedEps.isEmpty()) return@launch
            val curIdx = loadedEps.indexOfFirst { it.number == entry.currentEpisodeNum }.coerceAtLeast(0)
            val targetIdx = if (entry.completed && curIdx + 1 < loadedEps.size) curIdx + 1 else curIdx
            val startMs = if (targetIdx == curIdx && !entry.completed) entry.progressMs else 0L
            _activePlayer.value = PlayerLaunchRequest(
                anime = fullAnime,
                episodes = loadedEps,
                startIndex = targetIdx,
                startPositionMs = startMs,
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
        val siblingDownloads = downloadRepo.downloads.value
            .filter {
                it.animeId == entry.animeId &&
                    it.isDub == entry.isDub &&
                    it.status == DownloadStatus.COMPLETED
            }
            .sortedWith(compareBy { SearchUtils.episodeSortKey(it.episodeNum) })
            .map {
                EpisodeItem(
                    number = it.episodeNum,
                    videoId = it.videoId,
                    language = if (it.isDub) "ita" else "jpn",
                )
            }
        val eps = siblingDownloads.ifEmpty {
            listOf(EpisodeItem(number = entry.episodeNum, videoId = entry.videoId))
        }
        val idx = eps.indexOfFirst { it.number == entry.episodeNum }.coerceAtLeast(0)
        startPlayback(anime, eps, idx, resumeFromHistory = true)
    }

    fun closePlayer() {
        _activePlayer.value = null
    }

    // =========================================================================
    // Impostazioni & Aggiornamenti Automatici
    // =========================================================================

    fun setThemeMode(mode: ThemeMode) = settingsRepo.update { it.copy(themeMode = mode) }
    fun setDynamicColors(enabled: Boolean) = settingsRepo.update { it.copy(dynamicColors = enabled) }
    fun setAutoSkipIntro(enabled: Boolean) = settingsRepo.update { it.copy(autoSkipIntro = enabled) }
    fun setAutoSkipOutro(enabled: Boolean) = settingsRepo.update { it.copy(autoSkipOutro = enabled) }
    fun setAutoCheckUpdates(enabled: Boolean) = settingsRepo.update { it.copy(autoCheckUpdates = enabled) }

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
            runCatching { updater.checkForUpdate() }
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

        // Se l'APK è già stato scaricato e stavamo aspettando il permesso "Sorgenti sconosciute"
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
                    statusMessage = "Download aggiornamento ${info.tagName}...",
                    needsInstallPermission = false,
                )
            }
            runCatching {
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
                            statusMessage = "Download completato, avvio installazione...",
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
                        statusMessage = "Errore download aggiornamento: ${err.message}",
                    )
                }
            }
        }
    }
}
