package com.carloj005.awanime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.WatchHistoryEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val selectedFilter: LatestFilter = LatestFilter.ALL,
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

class AppViewModel : ViewModel() {

    private val client = AwAnimeApp.instance.animeUnityClient
    val historyRepo = AwAnimeApp.instance.historyRepository
    val downloadRepo = AwAnimeApp.instance.downloadRepository

    val history: StateFlow<List<WatchHistoryEntry>> = historyRepo.history
    val downloads: StateFlow<List<DownloadEntry>> = downloadRepo.downloads

    private val homeCache = mutableMapOf<LatestFilter, List<AnimeItem>>()
    private var homeJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null

    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _detailState = MutableStateFlow(DetailUiState())
    val detailState: StateFlow<DetailUiState> = _detailState.asStateFlow()

    private val _activePlayer = MutableStateFlow<PlayerLaunchRequest?>(null)
    val activePlayer: StateFlow<PlayerLaunchRequest?> = _activePlayer.asStateFlow()

    init {
        loadHome(LatestFilter.ALL)
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
            runCatching { client.getLatestEpisodes(filter) }
                .onSuccess { list ->
                    homeCache[filter] = list
                    _homeState.update {
                        it.copy(
                            selectedFilter = filter,
                            items = list,
                            isLoading = false,
                            error = null,
                            activeDomain = client.baseUrl.removePrefix("https://"),
                        )
                    }
                }
                .onFailure { err ->
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
                // Fallback offline: se ci sono episodi scaricati per questo anime, mostrali!
                val offlineEps = downloadRepo.downloads.value
                    .filter { it.animeId == anime.id && it.isDub == anime.isDub && it.status == com.carloj005.awanime.data.model.DownloadStatus.COMPLETED }
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
        val baseTitle = com.carloj005.awanime.data.network.SearchUtils.stripDubTags(current.title)
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

    fun resumeFromHistory(entry: WatchHistoryEntry) {
        viewModelScope.launch {
            val loadedEps = runCatching { client.getEpisodes(entry.anime.id, entry.anime.isDub) }
                .getOrElse {
                    // Fallback: almeno l'episodio corrente (es. se scaricato offline)
                    listOf(
                        EpisodeItem(
                            number = entry.currentEpisodeNum,
                            videoId = entry.currentVideoId,
                            language = if (entry.anime.isDub) "ita" else "jpn",
                        )
                    )
                }
            if (loadedEps.isEmpty()) return@launch
            val curIdx = loadedEps.indexOfFirst { it.number == entry.currentEpisodeNum }.coerceAtLeast(0)
            val targetIdx = if (entry.completed && curIdx + 1 < loadedEps.size) curIdx + 1 else curIdx
            val targetEp = loadedEps[targetIdx]
            val startMs = if (targetIdx == curIdx && !entry.completed) entry.progressMs else 0L
            _activePlayer.value = PlayerLaunchRequest(
                anime = entry.anime,
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
                    it.status == com.carloj005.awanime.data.model.DownloadStatus.COMPLETED
            }
            .sortedWith(compareBy { com.carloj005.awanime.data.network.SearchUtils.episodeSortKey(it.episodeNum) })
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
}
