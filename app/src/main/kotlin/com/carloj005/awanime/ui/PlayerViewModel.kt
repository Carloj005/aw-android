package com.carloj005.awanime.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.StreamCandidate
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.storage.DownloadRepository
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class PlayerSessionState(
    val currentIndex: Int = 0,
    val pendingSeekMs: Long = 0L,
    val isResolving: Boolean = true,
    val errorMessage: String? = null,
    val qualityBadge: String = "1080p HLS",
    val streamCandidates: List<StreamCandidate> = emptyList(),
    val candidateIdx: Int = 0,
    val referer: String = "https://vixcloud.cc/",
    val isLocalOfflineStream: Boolean = false,
    val hasAutoRefreshedToken: Boolean = false,
    val skipSegments: List<SkipSegment> = emptyList(),
    val skipFetchedWithDuration: Boolean = false,
    val autoSkippedKinds: Set<SkipKind> = emptySet(),
    val dismissedSkipKinds: Set<SkipKind> = emptySet(),
    val undoSkipInfo: Pair<String, Long>? = null,
    val preparedGeneration: Int = 0,
) {
    val activeCandidate: StreamCandidate?
        get() = streamCandidates.getOrNull(candidateIdx)
}

/**
 * Owns episode stream resolution, candidate fallback, single token refresh, skip-segment validation,
 * and watch-history persistence outside of Compose UI.
 */
class PlayerViewModel(
    private val client: AnimeUnityClient,
    private val historyRepo: HistoryRepository,
    private val downloadRepo: DownloadRepository,
    val mediaHttpClient: OkHttpClient,
) : ViewModel() {

    companion object {
        private val REFRESHABLE_HTTP_CODES = setOf(403, 410, 429, 500, 502, 503, 504)
        private const val MIN_PERSIST_POSITION_MS = 2_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as AwAnimeApp).container
                PlayerViewModel(
                    client = container.animeUnityClient,
                    historyRepo = container.historyRepository,
                    downloadRepo = container.downloadRepository,
                    mediaHttpClient = container.httpClients.mediaClient,
                )
            }
        }
    }

    private val _state = MutableStateFlow(PlayerSessionState())
    val state: StateFlow<PlayerSessionState> = _state.asStateFlow()

    private var resolveJob: Job? = null
    private var skipFallbackJob: Job? = null
    private var activeRequestKey: String? = null

    fun initializeRequest(request: PlayerLaunchRequest) {
        val key = "${request.anime.uniqueKey}_${request.startIndex}_${request.episodes.size}"
        if (activeRequestKey == key) return
        activeRequestKey = key
        _state.value = PlayerSessionState(
            currentIndex = request.startIndex.coerceIn(0, request.episodes.lastIndex),
            pendingSeekMs = request.startPositionMs,
        )
        resolveCurrentEpisode(request, resetAutoRefresh = true)
    }

    fun currentEpisode(request: PlayerLaunchRequest): EpisodeItem {
        val idx = _state.value.currentIndex.coerceIn(0, request.episodes.lastIndex)
        return request.episodes[idx]
    }

    fun switchEpisode(
        newIndex: Int,
        request: PlayerLaunchRequest,
        currentPosMs: Long,
        currentDurMs: Long,
    ): Boolean {
        if (newIndex !in request.episodes.indices) return false
        persistProgress(request, currentPosMs, currentDurMs)
        val nextEp = request.episodes[newIndex]
        val saved = historyRepo.getEpisodeProgress(request.anime.uniqueKey, nextEp.number)
        val startMs = if (saved != null && !saved.completed) saved.positionMs else 0L

        _state.update {
            it.copy(
                currentIndex = newIndex,
                pendingSeekMs = startMs,
                hasAutoRefreshedToken = false,
            )
        }
        resolveCurrentEpisode(request, resetAutoRefresh = true)
        return true
    }

    fun retryFromError(request: PlayerLaunchRequest, resumePositionMs: Long) {
        _state.update {
            it.copy(
                pendingSeekMs = maxOf(resumePositionMs, it.pendingSeekMs),
                hasAutoRefreshedToken = false,
            )
        }
        resolveCurrentEpisode(request, resetAutoRefresh = true)
    }

    fun onPlaybackError(
        request: PlayerLaunchRequest,
        httpCode: Int?,
        errorCodeName: String,
        resumePositionMs: Long,
    ) {
        val st = _state.value
        val nextCandIdx = st.candidateIdx + 1
        if (nextCandIdx < st.streamCandidates.size) {
            val nextCand = st.streamCandidates[nextCandIdx]
            _state.update {
                it.copy(
                    candidateIdx = nextCandIdx,
                    qualityBadge = nextCand.qualityLabel,
                    pendingSeekMs = resumePositionMs,
                    preparedGeneration = it.preparedGeneration + 1,
                )
            }
            return
        }

        if (!st.isLocalOfflineStream && !st.hasAutoRefreshedToken &&
            (httpCode == null || httpCode in REFRESHABLE_HTTP_CODES)
        ) {
            _state.update {
                it.copy(
                    hasAutoRefreshedToken = true,
                    pendingSeekMs = resumePositionMs,
                )
            }
            resolveCurrentEpisode(request, resetAutoRefresh = false)
            return
        }

        val detail = if (httpCode != null) "HTTP $httpCode" else errorCodeName
        _state.update {
            it.copy(
                pendingSeekMs = resumePositionMs,
                errorMessage = "Errore stream ($detail). Tocca Riprova.",
            )
        }
    }

    fun fetchSkipFallbackWithRealDuration(request: PlayerLaunchRequest, durationMs: Long) {
        val st = _state.value
        if (st.isResolving || st.skipFetchedWithDuration || st.skipSegments.isNotEmpty() || durationMs < 60_000L) {
            return
        }
        val ep = currentEpisode(request)
        _state.update { it.copy(skipFetchedWithDuration = true) }
        skipFallbackJob?.cancel()
        skipFallbackJob = viewModelScope.launch {
            val realSec = (durationMs / 1000L).toInt()
            val fallback = client.fetchValidatedSkipSegments(request.anime, ep.number, realSec)
            if (fallback.isNotEmpty()) {
                _state.update { it.copy(skipSegments = fallback) }
            }
        }
    }

    fun persistProgress(
        request: PlayerLaunchRequest,
        positionMs: Long,
        durationMs: Long,
        forceCompleted: Boolean = false,
    ) {
        val idx = _state.value.currentIndex.coerceIn(0, request.episodes.lastIndex)
        val ep = request.episodes[idx]
        val isAtLatestEpisode = idx >= request.episodes.lastIndex
        val enrichedAnime = request.anime.copy(
            releasedEpisodes = maxOf(request.anime.releasedEpisodes, request.episodes.size),
        )
        val safePos = if (forceCompleted && durationMs > 0L) durationMs else positionMs.coerceAtLeast(0L)
        if (durationMs > 0L || safePos > MIN_PERSIST_POSITION_MS) {
            historyRepo.updateProgress(
                anime = enrichedAnime,
                episodeNum = ep.number,
                videoId = ep.videoId,
                positionMs = safePos,
                durationMs = durationMs,
                isAtLatestEpisode = isAtLatestEpisode,
            )
        }
    }

    fun getHistoryEntry(animeUniqueKey: String) = historyRepo.getEntry(animeUniqueKey)

    fun recordAutoSkip(kind: SkipKind, label: String, originPosMs: Long) {
        _state.update {
            it.copy(
                autoSkippedKinds = it.autoSkippedKinds + kind,
                undoSkipInfo = label to originPosMs,
            )
        }
    }

    fun clearUndoSkip() {
        _state.update { it.copy(undoSkipInfo = null) }
    }

    fun dismissSkipKind(kind: SkipKind) {
        _state.update { it.copy(dismissedSkipKinds = it.dismissedSkipKinds + kind) }
    }

    private fun resolveCurrentEpisode(request: PlayerLaunchRequest, resetAutoRefresh: Boolean) {
        resolveJob?.cancel()
        skipFallbackJob?.cancel()

        val ep = currentEpisode(request)
        _state.update {
            it.copy(
                isResolving = true,
                errorMessage = null,
                streamCandidates = emptyList(),
                candidateIdx = 0,
                skipSegments = emptyList(),
                skipFetchedWithDuration = false,
                autoSkippedKinds = emptySet(),
                dismissedSkipKinds = emptySet(),
                undoSkipInfo = null,
                hasAutoRefreshedToken = if (resetAutoRefresh) false else it.hasAutoRefreshedToken,
            )
        }

        resolveJob = viewModelScope.launch {
            val localFile = downloadRepo.getLocalFileIfReady(request.anime.id, request.anime.isDub, ep.number)
            if (localFile != null) {
                val cand = StreamCandidate(
                    url = Uri.fromFile(localFile).toString(),
                    isHls = false,
                    qualityLabel = "OFFLINE",
                )
                _state.update {
                    it.copy(
                        isResolving = false,
                        isLocalOfflineStream = true,
                        qualityBadge = "OFFLINE",
                        streamCandidates = listOf(cand),
                        candidateIdx = 0,
                        skipFetchedWithDuration = ep.runtimeSeconds > 0,
                        preparedGeneration = it.preparedGeneration + 1,
                    )
                }
                if (ep.runtimeSeconds > 0) {
                    val validated = client.fetchValidatedSkipSegments(request.anime, ep.number, ep.runtimeSeconds)
                    if (validated.isNotEmpty()) {
                        _state.update { it.copy(skipSegments = validated) }
                    }
                }
                return@launch
            }

            runCatchingCancellable { client.resolveStream(request.anime.id, ep.videoId) }
                .onSuccess { res ->
                    val hasNativeSkips = res.skipSegments.isNotEmpty()
                    val willFetchWithEpRuntime = !hasNativeSkips && ep.runtimeSeconds > 0
                    _state.update {
                        it.copy(
                            isResolving = false,
                            isLocalOfflineStream = false,
                            referer = res.referer,
                            qualityBadge = res.qualityLabel,
                            streamCandidates = res.candidates,
                            candidateIdx = 0,
                            skipSegments = res.skipSegments,
                            skipFetchedWithDuration = hasNativeSkips || willFetchWithEpRuntime,
                            preparedGeneration = it.preparedGeneration + 1,
                        )
                    }
                    if (willFetchWithEpRuntime) {
                        val fallback = client.fetchValidatedSkipSegments(request.anime, ep.number, ep.runtimeSeconds)
                        if (fallback.isNotEmpty()) {
                            _state.update { it.copy(skipSegments = fallback) }
                        }
                    }
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(
                            isResolving = false,
                            errorMessage = err.message ?: "Impossibile ottenere lo stream dell'episodio",
                        )
                    }
                }
        }
    }
}
