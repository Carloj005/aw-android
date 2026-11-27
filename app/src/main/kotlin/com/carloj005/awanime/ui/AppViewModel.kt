package com.carloj005.awanime.ui

import android.content.ContentResolver
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
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AppBackupData
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.SearchFilter
import com.carloj005.awanime.data.model.UpdateInfo
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.resolveResumeTarget
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.network.OfflineException
import com.carloj005.awanime.data.network.SearchUtils
import com.carloj005.awanime.data.storage.DownloadRepository
import com.carloj005.awanime.data.storage.FavoritesRepository
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.storage.SettingsRepository
import com.carloj005.awanime.data.update.AppUpdater
import com.carloj005.awanime.ui.screens.SettingsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class HomeUiState(
    val selectedFilter: LatestFilter = LatestFilter.ALL,
    val heroCarousel: List<AnimeItem> = emptyList(),
    val items: List<AnimeItem> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val activeDomain: String = "animeunity.cx",
)

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
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

class AppViewModel(
    private val client: AnimeUnityClient,
    private val historyRepo: HistoryRepository,
    private val favoritesRepo: FavoritesRepository,
    private val downloadRepo: DownloadRepository,
    private val settingsRepo: SettingsRepository,
    private val updater: AppUpdater,
    private val cacheDir: File,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val KEY_SAVED_DETAIL_ID = "saved_detail_id"
        private const val KEY_SAVED_DETAIL_TITLE = "saved_detail_title"
        private const val KEY_SAVED_DETAIL_DUB = "saved_detail_dub"
        private const val MAX_RECENT_QUERIES = 10

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as AwAnimeApp
                val container: AppContainer = app.container
                AppViewModel(
                    client = container.animeUnityClient,
                    historyRepo = container.historyRepository,
                    favoritesRepo = container.favoritesRepository,
                    downloadRepo = container.downloadRepository,
                    settingsRepo = container.settingsRepository,
                    updater = container.appUpdater,
                    cacheDir = container.cacheDir,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }

    val history: StateFlow<List<WatchHistoryEntry>> = historyRepo.history
    val favorites: StateFlow<List<AnimeItem>> = favoritesRepo.favorites
    val downloads: StateFlow<List<DownloadEntry>> = downloadRepo.downloads
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    val shareBaseUrl: String get() = client.baseUrl

    private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
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

    private val _settingsFeedback = MutableStateFlow<String?>(null)
    val settingsFeedback: StateFlow<String?> = _settingsFeedback.asStateFlow()

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    init {
        loadHome(LatestFilter.ALL)
        restoreDetailIfNeeded()
        viewModelScope.launch {
            val loadedSettings = settingsRepo.awaitLoaded()
            _searchState.update { it.copy(recentQueries = loadedSettings.recentQueries) }
            if (loadedSettings.autoCheckUpdates) {
                checkForUpdates(manual = false)
            }
        }
    }

    private fun restoreDetailIfNeeded() {
        val savedId = savedStateHandle.get<String>(KEY_SAVED_DETAIL_ID)?.takeIf { it.isNotBlank() } ?: return
        val savedTitle = savedStateHandle.get<String>(KEY_SAVED_DETAIL_TITLE).orEmpty().ifBlank { "Anime" }
        val savedDub = savedStateHandle.get<Boolean>(KEY_SAVED_DETAIL_DUB) ?: false
        openAnimeDetail(
            AnimeItem(
                id = savedId,
                title = savedTitle,
                isDub = savedDub,
                audioLabel = if (savedDub) "Italiano" else "Giapponese",
            )
        )
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

    fun updateSearchQuery(newQuery: String) {
        _searchState.update { it.copy(query = newQuery) }
    }

    fun setSearchFilter(filter: SearchFilter) {
        _searchState.update { it.copy(filter = filter) }
    }

    fun clearRecentQueries() {
        settingsRepo.update { it.copy(recentQueries = emptyList()) }
        _searchState.update { it.copy(recentQueries = emptyList()) }
    }

    fun performSearch(rawQuery: String = _searchState.value.query) {
        val q = rawQuery.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        val updatedRecent = (listOf(q) + _searchState.value.recentQueries.filterNot { it.equals(q, ignoreCase = true) })
            .take(MAX_RECENT_QUERIES)
        settingsRepo.update { it.copy(recentQueries = updatedRecent) }
        _searchState.update { st ->
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
                    _searchState.update { it.copy(results = list, isLoading = false, error = null) }
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
        savedStateHandle[KEY_SAVED_DETAIL_ID] = anime.id
        savedStateHandle[KEY_SAVED_DETAIL_TITLE] = anime.title
        savedStateHandle[KEY_SAVED_DETAIL_DUB] = anime.isDub
        _detailState.value = DetailUiState(anime = anime, episodes = emptyList(), isLoading = true, error = null)

        detailJob = viewModelScope.launch {
            runCatching {
                val detailResult = runCatching { client.getAnimeDetails(anime.id, anime.isDub) }
                val detailError = detailResult.exceptionOrNull()
                if (detailError is OfflineException) throw detailError
                val fullAnime = detailResult.getOrDefault(anime)
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

    fun toggleFavorite(anime: AnimeItem) = favoritesRepo.toggleFavorite(anime)
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
        viewModelScope.launch {
            val fullAnime = runCatching { client.getAnimeDetails(anime.id, anime.isDub) }.getOrDefault(anime)
            val eps = runCatching { client.getEpisodes(fullAnime.id, fullAnime.isDub) }.getOrDefault(emptyList())
            val resumeTarget = resolveResumeTarget(eps, historyRepo.getEntry(fullAnime.uniqueKey))
            if (resumeTarget != null) {
                startPlayback(fullAnime, eps, resumeTarget.episodeIndex, resumeFromHistory = true)
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

    fun onSettingsEvent(
        event: SettingsEvent,
        onNavigateBack: () -> Unit,
        onLaunchExportBackup: () -> Unit,
        onLaunchImportBackup: () -> Unit,
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
            SettingsEvent.CheckUpdatesNow -> checkForUpdates(manual = true)
            SettingsEvent.OpenUpdateDialog -> showUpdateDialog()
            SettingsEvent.ExportBackup -> onLaunchExportBackup()
            SettingsEvent.ImportBackup -> onLaunchImportBackup()
            SettingsEvent.ClearCache -> clearAppCache()
        }
    }

    fun exportBackupToUri(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
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
                runCatching {
                    val rawText = contentResolver.openInputStream(uri)?.use { inp ->
                        inp.bufferedReader(Charsets.UTF_8).readText()
                    }.orEmpty()
                    importBackupJson(rawText).getOrThrow()
                }
            }
            _settingsFeedback.value = result.fold(
                onSuccess = { it },
                onFailure = { "File backup non valido: ${it.message}" },
            )
        }
    }

    fun buildBackupJson(): String {
        val data = AppBackupData(
            schemaVersion = 1,
            exportedAt = System.currentTimeMillis(),
            settings = settings.value,
            history = history.value,
            favorites = favorites.value,
        )
        return backupJson.encodeToString(data)
    }

    fun importBackupJson(rawText: String): Result<String> {
        return runCatching {
            val parsed = backupJson.decodeFromString<AppBackupData>(rawText)
            historyRepo.mergeImport(parsed.history)
            favoritesRepo.mergeImport(parsed.favorites)
            settingsRepo.replace(parsed.settings)
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
