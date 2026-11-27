package com.carloj005.awanime.ui.screens

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.StreamCandidate
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.storage.DownloadRepository
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.ui.PlayerLaunchRequest
import com.carloj005.awanime.ui.screens.player.PipCommand
import com.carloj005.awanime.ui.screens.player.PlayerBufferingIndicator
import com.carloj005.awanime.ui.screens.player.PlayerControlsOverlay
import com.carloj005.awanime.ui.screens.player.PlayerEpisodeDrawer
import com.carloj005.awanime.ui.screens.player.PlayerErrorCard
import com.carloj005.awanime.ui.screens.player.PlayerFloatingSkipButton
import com.carloj005.awanime.ui.screens.player.PlayerGestureHud
import com.carloj005.awanime.ui.screens.player.PlayerNextEpisodeCard
import com.carloj005.awanime.ui.screens.player.PlayerOverlayActions
import com.carloj005.awanime.ui.screens.player.PlayerOverlayState
import com.carloj005.awanime.ui.screens.player.PlayerSeekFeedbackPill
import com.carloj005.awanime.ui.screens.player.PlayerUndoSkipPill
import com.carloj005.awanime.ui.screens.player.PlayerUnlockPill
import com.carloj005.awanime.ui.screens.player.ResilientStreamErrorPolicy
import com.carloj005.awanime.ui.screens.player.VerticalGestureZone
import com.carloj005.awanime.ui.screens.player.buildMediaItem
import com.carloj005.awanime.ui.screens.player.findActivity
import com.carloj005.awanime.ui.screens.player.findHttpStatusCode
import com.carloj005.awanime.util.runCatchingCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    request: PlayerLaunchRequest,
    settings: AppSettings,
    client: AnimeUnityClient,
    historyRepo: HistoryRepository,
    downloadRepo: DownloadRepository,
    isInPipMode: Boolean = false,
    pipCommands: Flow<PipCommand> = emptyFlow(),
    onPlayingStateChanged: (Boolean) -> Unit = {},
    onEnterPip: () -> Unit = {},
    onClose: () -> Unit,
) {
    if (request.episodes.isEmpty()) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var currentIndex by remember(request) { mutableIntStateOf(request.startIndex.coerceIn(0, request.episodes.lastIndex)) }
    var pendingSeekMs by remember(request) { mutableLongStateOf(request.startPositionMs) }
    val currentEpisode = request.episodes[currentIndex.coerceIn(0, request.episodes.lastIndex)]
    val hasNextEpisode = currentIndex + 1 < request.episodes.size

    var isResolving by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var qualityBadge by remember { mutableStateOf("1080p HLS") }
    var streamCandidates by remember { mutableStateOf<List<StreamCandidate>>(emptyList()) }
    var candidateIdx by remember { mutableIntStateOf(0) }
    var retryCounter by remember { mutableIntStateOf(0) }
    var hasAutoRefreshedToken by remember(currentIndex) { mutableStateOf(false) }
    var isLocalOfflineStream by remember(currentIndex) { mutableStateOf(false) }

    var skipSegments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var skipFetchedWithDuration by remember(currentIndex, retryCounter) { mutableStateOf(false) }
    var autoSkippedKinds by remember(currentIndex) { mutableStateOf<Set<SkipKind>>(emptySet()) }
    var dismissedSkipKinds by remember(currentIndex) { mutableStateOf<Set<SkipKind>>(emptySet()) }
    var undoSkipInfo by remember { mutableStateOf<Pair<String, Long>?>(null) }

    var outroCountdownCancelled by remember(currentIndex) { mutableStateOf(false) }
    var outroCountdownSeconds by remember(currentIndex) { mutableIntStateOf(5) }
    var outroCountdownActive by remember(currentIndex) { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var showUnlockPill by remember { mutableStateOf(false) }
    var episodeDrawerOpen by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    val speeds = remember { listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }
    var speedIdx by remember { mutableIntStateOf(1) }
    val resizeModes = remember {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Adatta",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Riempi",
        )
    }
    var resizeIdx by remember { mutableIntStateOf(0) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    var brightnessLevel by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.55f
        )
    }
    val maxVolume = remember(audioManager) { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volumeFraction by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat())
    }
    var activeGestureHud by remember { mutableStateOf<Pair<Boolean, Float>?>(null) }

    DisposableEffect(activity) {
        val prevOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val prevBrightness = activity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.let { win ->
                val attrs = win.attributes
                attrs.screenBrightness = prevBrightness
                win.attributes = attrs
            }
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = prevOrientation
        }
    }

    LaunchedEffect(isInPipMode, activity) {
        if (!isInPipMode) {
            val insetsController = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            insetsController?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    val httpDataSourceFactory = remember(client) {
        OkHttpDataSource.Factory(client.mediaOkHttpClient)
            .setUserAgent(AnimeUnityClient.USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://vixcloud.cc/",
                    "Origin" to "https://vixcloud.cc",
                )
            )
    }

    val exoPlayer = remember(context) {
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(ResilientStreamErrorPolicy())
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 35_000, 1_000, 2_000)
            .setBackBuffer(20_000, true)
            .build()
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoSize(3840, 2160)
            )
        }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    val latestEpisode by rememberUpdatedState(currentEpisode)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestOnPlayingChanged by rememberUpdatedState(onPlayingStateChanged)

    fun persistCurrentProgress(forceCompleted: Boolean = false) {
        val ep = latestEpisode
        val dur = exoPlayer.duration.takeIf { it > 0L } ?: latestDurationMs
        val pos = if (forceCompleted && dur > 0L) dur else exoPlayer.currentPosition.coerceAtLeast(0L)
        if (dur > 0L || pos > 2_000L) {
            historyRepo.updateProgress(
                anime = request.anime,
                episodeNum = ep.number,
                videoId = ep.videoId,
                positionMs = pos,
                durationMs = dur,
            )
        }
    }

    fun switchEpisode(newIndex: Int, persistCurrent: Boolean = true) {
        if (newIndex !in request.episodes.indices) return
        if (persistCurrent) {
            persistCurrentProgress()
        }
        val nextEp = request.episodes[newIndex]
        val saved = historyRepo.getEpisodeProgress(request.anime.uniqueKey, nextEp.number)
        pendingSeekMs = if (saved != null && !saved.completed) saved.positionMs else 0L
        episodeDrawerOpen = false
        outroCountdownCancelled = false
        outroCountdownActive = false
        currentIndex = newIndex
    }

    fun skipSegmentNow(segment: SkipSegment, wasAuto: Boolean = false) {
        val fromPos = exoPlayer.currentPosition.coerceAtLeast(0L)
        if (segment.kind == SkipKind.OUTRO && hasNextEpisode && durationMs > 0L && segment.endMs >= durationMs - 5_000L) {
            switchEpisode(currentIndex + 1)
            return
        }
        val target = if (durationMs > 1_000L) {
            segment.endMs.coerceIn(0L, durationMs - 1_000L)
        } else {
            segment.endMs.coerceAtLeast(0L)
        }
        exoPlayer.seekTo(target)
        positionMs = target
        if (wasAuto) {
            val label = when (segment.kind) {
                SkipKind.INTRO -> "Sigla saltata"
                SkipKind.OUTRO -> "Outro saltata"
                SkipKind.RECAP -> "Riepilogo saltato"
            }
            undoSkipInfo = label to fromPos
        } else {
            seekFeedback = segment.kind.label
        }
    }

    fun seekBy(deltaMs: Long, label: String) {
        val rawTarget = exoPlayer.currentPosition + deltaMs
        val target = if (durationMs > 1_000L) {
            rawTarget.coerceIn(0L, durationMs - 1_000L)
        } else {
            rawTarget.coerceAtLeast(0L)
        }
        exoPlayer.seekTo(target)
        positionMs = target
        seekFeedback = label
    }

    DisposableEffect(exoPlayer, currentIndex, streamCandidates, candidateIdx, isLocalOfflineStream, hasAutoRefreshedToken) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
                if (state == Player.STATE_ENDED) {
                    persistCurrentProgress(forceCompleted = true)
                    if (hasNextEpisode && !outroCountdownCancelled) {
                        switchEpisode(currentIndex + 1, persistCurrent = false)
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                latestOnPlayingChanged(playing)
                if (!playing) persistCurrentProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                val resumePos = exoPlayer.currentPosition.coerceAtLeast(positionMs).coerceAtLeast(pendingSeekMs)
                val httpCode = findHttpStatusCode(error)
                val nextCand = candidateIdx + 1
                if (nextCand < streamCandidates.size) {
                    candidateIdx = nextCand
                    val cand = streamCandidates[nextCand]
                    qualityBadge = cand.qualityLabel
                    exoPlayer.setMediaItem(buildMediaItem(cand))
                    exoPlayer.prepare()
                    if (resumePos > 0L) exoPlayer.seekTo(resumePos)
                    exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
                    exoPlayer.playWhenReady = true
                } else if (!isLocalOfflineStream && !hasAutoRefreshedToken &&
                    (httpCode == null || httpCode in setOf(403, 410, 429, 500, 502, 503, 504))
                ) {
                    hasAutoRefreshedToken = true
                    pendingSeekMs = resumePos
                    retryCounter++
                } else {
                    pendingSeekMs = resumePos
                    val detail = if (httpCode != null) "HTTP $httpCode" else error.errorCodeName
                    errorMessage = "Errore stream ($detail). Tocca Riprova."
                    isBuffering = false
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            latestOnPlayingChanged(false)
            persistCurrentProgress()
            exoPlayer.release()
        }
    }

    // In PiP Android mette l'Activity in PAUSED ma non in STOPPED:
    // salva su ON_PAUSE senza mettere in pausa, e metti in pausa solo su ON_STOP.
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                persistCurrentProgress()
            } else if (event == Lifecycle.Event.ON_STOP) {
                persistCurrentProgress()
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pipCommands, exoPlayer) {
        pipCommands.collect { cmd ->
            when (cmd) {
                PipCommand.TOGGLE_PLAY -> {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                }
                PipCommand.REWIND_10 -> {
                    val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                    exoPlayer.seekTo(target)
                    positionMs = target
                }
                PipCommand.FORWARD_10 -> {
                    val maxPos = if (latestDurationMs > 1_000L) latestDurationMs - 1_000L else Long.MAX_VALUE
                    val target = (exoPlayer.currentPosition + 10_000L).coerceIn(0L, maxPos)
                    exoPlayer.seekTo(target)
                    positionMs = target
                }
            }
        }
    }

    LaunchedEffect(currentIndex, retryCounter) {
        isResolving = true
        errorMessage = null
        streamCandidates = emptyList()
        skipSegments = emptyList()
        skipFetchedWithDuration = false
        autoSkippedKinds = emptySet()
        dismissedSkipKinds = emptySet()
        undoSkipInfo = null
        outroCountdownActive = false
        outroCountdownCancelled = false
        candidateIdx = 0
        exoPlayer.stop()

        val ep = request.episodes[currentIndex.coerceIn(0, request.episodes.lastIndex)]
        val localFile = downloadRepo.getLocalFileIfReady(request.anime.id, request.anime.isDub, ep.number)
        if (localFile != null) {
            isLocalOfflineStream = true
            qualityBadge = "OFFLINE"
            val cand = StreamCandidate(url = Uri.fromFile(localFile).toString(), isHls = false, qualityLabel = "OFFLINE")
            streamCandidates = listOf(cand)
            isResolving = false
            exoPlayer.setMediaItem(buildMediaItem(cand))
            exoPlayer.prepare()
            if (pendingSeekMs > 0L) exoPlayer.seekTo(pendingSeekMs)
            exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
            exoPlayer.playWhenReady = true
            if (ep.runtimeSeconds > 0) {
                skipFetchedWithDuration = true
                val validated = client.fetchValidatedSkipSegments(request.anime, ep.number, ep.runtimeSeconds)
                if (validated.isNotEmpty()) skipSegments = validated
            }
            return@LaunchedEffect
        }

        isLocalOfflineStream = false
        runCatchingCancellable { client.resolveStream(request.anime.id, ep.videoId) }
            .onSuccess { res ->
                val origin = res.referer.trimEnd('/')
                httpDataSourceFactory.setDefaultRequestProperties(
                    mapOf("Referer" to res.referer, "Origin" to origin)
                )
                qualityBadge = res.qualityLabel
                streamCandidates = res.candidates
                skipSegments = res.skipSegments
                candidateIdx = 0
                isResolving = false
                exoPlayer.setMediaItem(buildMediaItem(res.candidates.first()))
                exoPlayer.prepare()
                if (pendingSeekMs > 0L) exoPlayer.seekTo(pendingSeekMs)
                exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
                exoPlayer.playWhenReady = true

                if (res.skipSegments.isNotEmpty()) {
                    skipFetchedWithDuration = true
                } else if (ep.runtimeSeconds > 0) {
                    skipFetchedWithDuration = true
                    val fallback = client.fetchValidatedSkipSegments(request.anime, ep.number, ep.runtimeSeconds)
                    if (fallback.isNotEmpty()) skipSegments = fallback
                }
            }
            .onFailure { err ->
                isResolving = false
                errorMessage = err.message ?: "Impossibile ottenere lo stream dell'episodio"
            }
    }

    LaunchedEffect(durationMs, currentIndex, retryCounter, skipFetchedWithDuration, isResolving) {
        if (!isResolving && !skipFetchedWithDuration && skipSegments.isEmpty() && durationMs >= 60_000L) {
            skipFetchedWithDuration = true
            val realSec = (durationMs / 1000L).toInt()
            val fallback = client.fetchValidatedSkipSegments(request.anime, currentEpisode.number, realSec)
            if (fallback.isNotEmpty()) skipSegments = fallback
        }
    }

    val activeSkipSegment = remember(positionMs, skipSegments, dismissedSkipKinds) {
        skipSegments.firstOrNull { it.kind !in dismissedSkipKinds && it.contains(positionMs) }
    }

    LaunchedEffect(activeSkipSegment, settings.autoSkipIntro, settings.autoSkipOutro, isPlaying, currentEpisode.number) {
        val seg = activeSkipSegment ?: return@LaunchedEffect
        if (!isPlaying || seg.kind in autoSkippedKinds) return@LaunchedEffect
        val isEpisodeOne = currentEpisode.number.trim() == "1"
        val allowIntroAuto = settings.autoSkipIntro && (seg.verified || !isEpisodeOne)
        val shouldAuto = (seg.kind == SkipKind.INTRO && allowIntroAuto) ||
            (seg.kind == SkipKind.OUTRO && settings.autoSkipOutro)
        if (shouldAuto) {
            autoSkippedKinds = autoSkippedKinds + seg.kind
            skipSegmentNow(seg, wasAuto = true)
        }
    }

    val inOutroForNextEp = hasNextEpisode && !outroCountdownCancelled && (
        activeSkipSegment?.kind == SkipKind.OUTRO ||
            (durationMs > 60_000L && positionMs >= durationMs - 15_000L)
        )
    LaunchedEffect(inOutroForNextEp, isPlaying, currentIndex) {
        if (inOutroForNextEp && isPlaying) {
            outroCountdownActive = true
            for (sec in 5 downTo 1) {
                outroCountdownSeconds = sec
                delay(1000L)
                if (outroCountdownCancelled || !isPlaying) {
                    outroCountdownActive = false
                    return@LaunchedEffect
                }
            }
            outroCountdownActive = false
            switchEpisode(currentIndex + 1)
        } else {
            outroCountdownActive = false
        }
    }

    LaunchedEffect(exoPlayer, currentIndex) {
        var tick = 0
        while (true) {
            if (!isSeeking) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
                if (durationMs > 0L) {
                    sliderPosition = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                }
            }
            tick++
            if (tick % 12 == 0 && exoPlayer.isPlaying) persistCurrentProgress()
            delay(400L)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, episodeDrawerOpen, isSeeking) {
        if (!controlsVisible && isSeeking) isSeeking = false
        if (controlsVisible && isPlaying && !episodeDrawerOpen && !isSeeking) {
            delay(3500L)
            controlsVisible = false
        }
    }
    LaunchedEffect(showUnlockPill) {
        if (showUnlockPill) {
            delay(2500L)
            showUnlockPill = false
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(750L)
            seekFeedback = null
        }
    }
    LaunchedEffect(undoSkipInfo) {
        if (undoSkipInfo != null) {
            delay(4500L)
            undoSkipInfo = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isScreenLocked, episodeDrawerOpen, isInPipMode) {
                detectTapGestures(
                    onTap = {
                        if (isInPipMode) return@detectTapGestures
                        when {
                            isScreenLocked -> showUnlockPill = !showUnlockPill
                            episodeDrawerOpen -> episodeDrawerOpen = false
                            else -> controlsVisible = !controlsVisible
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!isInPipMode && !isScreenLocked && !episodeDrawerOpen) {
                            when {
                                offset.x < size.width * 0.38f -> seekBy(-10_000L, "-10s")
                                offset.x > size.width * 0.62f -> seekBy(10_000L, "+10s")
                            }
                        }
                    },
                )
            }
            .pointerInput(isScreenLocked, episodeDrawerOpen, isInPipMode) {
                var activeZone = VerticalGestureZone.NONE
                detectVerticalDragGestures(
                    onDragStart = { startOffset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val inSafeVerticalBand = startOffset.y in (h * 0.18f)..(h * 0.82f)
                        activeZone = when {
                            !inSafeVerticalBand -> VerticalGestureZone.NONE
                            startOffset.x in (w * 0.10f)..(w * 0.35f) -> VerticalGestureZone.BRIGHTNESS
                            startOffset.x in (w * 0.65f)..(w * 0.90f) -> {
                                volumeFraction = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat()
                                VerticalGestureZone.VOLUME
                            }
                            else -> VerticalGestureZone.NONE
                        }
                    },
                    onDragEnd = {
                        activeZone = VerticalGestureZone.NONE
                        activeGestureHud = null
                    },
                    onDragCancel = {
                        activeZone = VerticalGestureZone.NONE
                        activeGestureHud = null
                    },
                    onVerticalDrag = { _, dragAmount ->
                        if (isInPipMode || isScreenLocked || episodeDrawerOpen || activeZone == VerticalGestureZone.NONE) {
                            return@detectVerticalDragGestures
                        }
                        val delta = -dragAmount / (size.height * 0.65f)
                        if (activeZone == VerticalGestureZone.BRIGHTNESS) {
                            brightnessLevel = (brightnessLevel + delta).coerceIn(0.02f, 1.0f)
                            activity?.window?.let { win ->
                                val attrs = win.attributes
                                attrs.screenBrightness = brightnessLevel
                                win.attributes = attrs
                            }
                            activeGestureHud = true to brightnessLevel
                        } else if (activeZone == VerticalGestureZone.VOLUME) {
                            volumeFraction = (volumeFraction + delta).coerceIn(0f, 1f)
                            val targetVol = (volumeFraction * maxVolume).toInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            activeGestureHud = false to volumeFraction
                        }
                    },
                )
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = resizeModes[resizeIdx].first
                }
            },
            update = { view -> view.resizeMode = resizeModes[resizeIdx].first },
            modifier = Modifier.fillMaxSize(),
        )

        if (isResolving || isBuffering) {
            PlayerBufferingIndicator(
                isResolving = isResolving,
                isInPipMode = isInPipMode,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (!isInPipMode) {
            activeGestureHud?.let { (isBrightness, fraction) ->
                PlayerGestureHud(
                    isBrightness = isBrightness,
                    fraction = fraction,
                    modifier = Modifier.align(if (isBrightness) Alignment.CenterStart else Alignment.CenterEnd),
                )
            }
            seekFeedback?.let { label ->
                PlayerSeekFeedbackPill(
                    label = label,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        AnimatedVisibility(
            visible = undoSkipInfo != null && !isScreenLocked && !isInPipMode,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = if (controlsVisible) 92.dp else 28.dp),
        ) {
            undoSkipInfo?.let { (label, origMs) ->
                PlayerUndoSkipPill(
                    label = label,
                    onUndo = {
                        exoPlayer.seekTo(origMs)
                        positionMs = origMs
                        undoSkipInfo = null
                    },
                )
            }
        }

        val showFloatingSkipButton = activeSkipSegment != null &&
            !isScreenLocked &&
            !episodeDrawerOpen &&
            !outroCountdownActive &&
            !isInPipMode &&
            errorMessage == null

        AnimatedVisibility(
            visible = showFloatingSkipButton,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = if (controlsVisible) 96.dp else 32.dp),
        ) {
            activeSkipSegment?.let { seg ->
                PlayerFloatingSkipButton(
                    segment = seg,
                    onSkip = { skipSegmentNow(seg, wasAuto = false) },
                    onDismiss = { dismissedSkipKinds = dismissedSkipKinds + seg.kind },
                )
            }
        }

        AnimatedVisibility(
            visible = outroCountdownActive && hasNextEpisode && !isScreenLocked && !episodeDrawerOpen && !isInPipMode,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = if (controlsVisible) 96.dp else 28.dp),
        ) {
            request.episodes.getOrNull(currentIndex + 1)?.let { nextEp ->
                PlayerNextEpisodeCard(
                    nextEpisodeNumber = nextEp.number,
                    countdownSeconds = outroCountdownSeconds,
                    onCancel = {
                        outroCountdownCancelled = true
                        outroCountdownActive = false
                    },
                    onWatchNow = { switchEpisode(currentIndex + 1) },
                )
            }
        }

        AnimatedVisibility(
            visible = isScreenLocked && showUnlockPill && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
        ) {
            PlayerUnlockPill(
                onUnlock = {
                    isScreenLocked = false
                    showUnlockPill = false
                    controlsVisible = true
                },
            )
        }

        if (!isInPipMode) {
            errorMessage?.let { err ->
                PlayerErrorCard(
                    message = err,
                    onClose = {
                        persistCurrentProgress()
                        onClose()
                    },
                    onRetry = {
                        pendingSeekMs = positionMs.coerceAtLeast(pendingSeekMs)
                        hasAutoRefreshedToken = false
                        retryCounter++
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !isScreenLocked && !episodeDrawerOpen && !isInPipMode && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControlsOverlay(
                state = PlayerOverlayState(
                    animeTitle = request.anime.title,
                    episodeNumber = currentEpisode.number,
                    episodeCount = request.episodes.size,
                    currentIndex = currentIndex,
                    hasNextEpisode = hasNextEpisode,
                    qualityBadge = qualityBadge,
                    hasSkipSegments = skipSegments.isNotEmpty(),
                    speedLabel = "${speeds[speedIdx]}x",
                    resizeLabel = resizeModes[resizeIdx].second,
                    isPlaying = isPlaying,
                    isSeeking = isSeeking,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    sliderPosition = sliderPosition,
                ),
                actions = PlayerOverlayActions(
                    onBack = {
                        persistCurrentProgress()
                        onClose()
                    },
                    onOpenEpisodeDrawer = { episodeDrawerOpen = true },
                    onCycleSpeed = {
                        speedIdx = (speedIdx + 1) % speeds.size
                        exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
                    },
                    onCycleResize = { resizeIdx = (resizeIdx + 1) % resizeModes.size },
                    onEnterPip = onEnterPip,
                    onLockScreen = {
                        isScreenLocked = true
                        controlsVisible = false
                        showUnlockPill = true
                    },
                    onPreviousEpisode = { switchEpisode(currentIndex - 1) },
                    onNextEpisode = { switchEpisode(currentIndex + 1) },
                    onRewind10 = { seekBy(-10_000L, "-10s") },
                    onForward10 = { seekBy(10_000L, "+10s") },
                    onTogglePlayPause = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    onQuickSkip85 = { seekBy(85_000L, "+85s") },
                    onSeekPreview = { value ->
                        isSeeking = true
                        sliderPosition = value
                    },
                    onSeekCommit = {
                        if (durationMs > 1_000L) {
                            val target = (sliderPosition * durationMs).toLong().coerceIn(0L, durationMs - 1_000L)
                            exoPlayer.seekTo(target)
                            positionMs = target
                        }
                        isSeeking = false
                    },
                ),
            )
        }

        AnimatedVisibility(
            visible = episodeDrawerOpen && !isInPipMode,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PlayerEpisodeDrawer(
                episodes = request.episodes,
                currentIndex = currentIndex,
                historyEntry = historyRepo.getEntry(request.anime.uniqueKey),
                onSelectEpisode = { switchEpisode(it) },
                onClose = { episodeDrawerOpen = false },
            )
        }
    }
}
