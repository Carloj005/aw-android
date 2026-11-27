package com.carloj005.awanime.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AppBackupData
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.SearchFilter
import com.carloj005.awanime.data.model.ThemeMode
import com.carloj005.awanime.data.model.UpdateInfo
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.resolveNextEpisodeIndex
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.network.SearchUtils
import com.carloj005.awanime.data.storage.DownloadRepository
import com.carloj005.awanime.data.storage.FavoritesRepository
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.storage.SettingsRepository
import com.carloj005.awanime.data.update.AppUpdater
import com.carloj005.awanime.util.runCatchingCancellable
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

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
    val submittedQuery: String = "",
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
) : ViewModel() {

    constructor() : this(
        client = AwAnimeApp.instance.animeUnityClient,
        historyRepo = AwAnimeApp.instance.historyRepository,
        favoritesRepo = AwAnimeApp.instance.favoritesRepository,
        downloadRepo = AwAnimeApp.instance.downloadRepository,
        settingsRepo = AwAnimeApp.instance.settingsRepository,
        updater = AwAnimeApp.instance.appUpdater,
        cacheDir = AwAnimeApp.instance.cacheDir,
    )

    val history: StateFlow<List<WatchHistoryEntry>> = historyRepo.history
    val favorites: StateFlow<List<AnimeItem>> = favoritesRepo.favorites
    val downloads: StateFlow<List<DownloadEntry>> = downloadRepo.downloads
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    val activeBaseUrl: String get() = client.baseUrl

    private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val homeCache = mutableMapOf<LatestFilter, List<AnimeItem>>()
    private var homeJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var playbackLaunchJob: Job? = null
    private var updateJob: Job? = null
    private var backupJob: Job? = null

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
        viewModelScope.launch {
            settings.collect { currentSettings ->
                _searchState.update { it.copy(recentQueries = currentSettings.recentQueries) }
            }
        }
        viewModelScope.launch {
            settingsRepo.awaitLoaded()
            if (settings.value.autoCheckUpdates) {
                checkForUpdates(manual = false)
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
                val carousel = runCatchingCancellable {
                    client.getHomeCarousel(forceRefresh = forceRefresh)
                }.getOrDefault(emptyList())
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
    }

    fun performSearch(rawQuery: String = _searchState.value.query) {
        val q = rawQuery.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        val updatedRecent = (listOf(q) + settings.value.recentQueries.filterNot { it.equals(q, ignoreCase = true) }).take(10)
        settingsRepo.update { it.copy(recentQueries = updatedRecent) }
        _searchState.update { st ->
            st.copy(
                query = q,
                submittedQuery = q,
                isLoading = true,
                hasSearched = true,
                error = null,
            )
        }
        searchJob = viewModelScope.launch {
            runCatchingCancellable { client.search(q) }
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
            runCatchingCancellable {
                val fullAnime = runCatchingCancellable { client.getAnimeDetails(anime.id, anime.isDub) }
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
                    .sortedWith(compareBy { SearchUtils.episodeSortKey(it.episodeNum) })
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

    fun toggleFavorite(anime: AnimeItem) {
        favoritesRepo.toggleFavorite(anime)
    }

    fun removeFavorite(uniqueKey: String) {
        favoritesRepo.remove(uniqueKey)
    }

    fun removeHistoryEntry(uniqueKey: String) {
        historyRepo.remove(uniqueKey)
    }

    fun enqueueDownload(anime: AnimeItem, episode: EpisodeItem) {
        downloadRepo.enqueueDownload(anime, episode)
    }

    fun retryDownload(entry: DownloadEntry) {
        downloadRepo.retryDownload(entry)
    }

    fun cancelDownload(key: String) {
        downloadRepo.cancelDownload(key)
    }

    fun deleteDownload(key: String) {
        downloadRepo.deleteDownload(key)
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
        playbackLaunchJob?.cancel()
        playbackLaunchJob = viewModelScope.launch {
            val fullAnime = runCatchingCancellable { client.getAnimeDetails(anime.id, anime.isDub) }
                .getOrDefault(anime)
            val eps = runCatchingCancellable { client.getEpisodes(fullAnime.id, fullAnime.isDub) }
                .getOrDefault(emptyList())
            if (eps.isNotEmpty()) {
                val hist = historyRepo.getEntry(fullAnime.uniqueKey)
                val targetIdx = resolveNextEpisodeIndex(eps, hist)
                startPlayback(fullAnime, eps, targetIdx, resumeFromHistory = true)
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
            val targetIdx = resolveNextEpisodeIndex(loadedEps, entry)
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
        playbackLaunchJob?.cancel()
        _activePlayer.value = null
    }

    fun setThemeMode(mode: ThemeMode) = settingsRepo.update { it.copy(themeMode = mode) }
    fun setDynamicColors(enabled: Boolean) = settingsRepo.update { it.copy(dynamicColors = enabled) }
    fun setAutoSkipIntro(enabled: Boolean) = settingsRepo.update { it.copy(autoSkipIntro = enabled) }
    fun setAutoSkipOutro(enabled: Boolean) = settingsRepo.update { it.copy(autoSkipOutro = enabled) }
    fun setAutoCheckUpdates(enabled: Boolean) = settingsRepo.update { it.copy(autoCheckUpdates = enabled) }
    fun setWifiOnlyDownloads(enabled: Boolean) = settingsRepo.update { it.copy(wifiOnlyDownloads = enabled) }
    fun setAutoPipEnabled(enabled: Boolean) = settingsRepo.update { it.copy(autoPipEnabled = enabled) }

    fun setSettingsFeedback(message: String?) {
        _settingsFeedback.value = message
    }

    fun buildBackupJson(): String {
        val data = AppBackupData(
            schemaVersion = AppBackupData.CURRENT_SCHEMA_VERSION,
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
            if (parsed.schemaVersion > AppBackupData.CURRENT_SCHEMA_VERSION) {
                throw IllegalArgumentException(
                    "Versione backup (${parsed.schemaVersion}) non supportata da questa versione dell'app."
                )
            }
            historyRepo.mergeImport(parsed.history)
            favoritesRepo.mergeImport(parsed.favorites)
            settingsRepo.replace(parsed.settings)
            "Ripristino completato: ${parsed.history.size} cronologia, ${parsed.favorites.size} preferiti."
        }
    }

    fun exportBackupToUri(contentResolver: ContentResolver, uri: Uri) {
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            runCatchingCancellable {
                val payload = buildBackupJson()
                withContext(Dispatchers.IO) {
                    val out = contentResolver.openOutputStream(uri)
                        ?: throw IOException("Impossibile aprire il file di destinazione")
                    out.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                _settingsFeedback.value = "Backup esportato con successo!"
            }.onFailure { err ->
                _settingsFeedback.value = "Errore esportazione backup: ${err.message}"
            }
        }
    }

    fun importBackupFromUri(contentResolver: ContentResolver, uri: Uri) {
        backupJob?.cancel()
        backupJob = viewModelScope.launch {
            runCatchingCancellable {
                val rawText = withContext(Dispatchers.IO) {
                    readTextWithSizeLimit(contentResolver, uri, MAX_BACKUP_BYTES)
                }
                importBackupJson(rawText).getOrThrow()
            }.onSuccess { msg ->
                _settingsFeedback.value = msg
            }.onFailure { err ->
                _settingsFeedback.value = "File backup non valido: ${err.message}"
            }
        }
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheSizeBytes.value = withContext(Dispatchers.IO) { computeDirBytes(cacheDir) }
        }
    }

    fun clearAppCache() {
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) {
                deleteDirContents(cacheDir)
                computeDirBytes(cacheDir)
            }
            _cacheSizeBytes.value = remaining
            _settingsFeedback.value = "Cache svuotata con successo."
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

    companion object {
        private const val MAX_BACKUP_BYTES = 10 * 1024 * 1024 // 10 MB

        private fun readTextWithSizeLimit(
            contentResolver: ContentResolver,
            uri: Uri,
            maxBytes: Int,
        ): String {
            val input = contentResolver.openInputStream(uri)
                ?: throw IOException("Impossibile leggere il file selezionato")
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buf)
                    if (read == -1) break
                    total += read
                    if (total > maxBytes) {
                        throw IllegalArgumentException("File troppo grande (massimo ${maxBytes / (1024 * 1024)} MB)")
                    }
                    out.write(buf, 0, read)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        }

        private fun computeDirBytes(dir: File): Long {
            var total = 0L
            dir.listFiles()?.forEach { file ->
                total += if (file.isDirectory) computeDirBytes(file) else file.length()
            }
            return total
        }

        private fun deleteDirContents(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    deleteDirContents(file)
                }
                file.delete()
            }
        }
    }
}
