package com.carloj005.awanime.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.StreamCandidate
import com.carloj005.awanime.data.model.formatTimeMs
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.ui.PlayerLaunchRequest
import com.carloj005.awanime.ui.components.BadgePill
import com.carloj005.awanime.ui.theme.BadgeGreen
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun buildMediaItem(candidate: StreamCandidate): MediaItem {
    val builder = MediaItem.Builder().setUri(Uri.parse(candidate.url))
    if (candidate.isHls) {
        builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    }
    return builder.build()
}

private fun findHttpStatusCode(error: Throwable?): Int? {
    var curr = error
    while (curr != null) {
        if (curr is HttpDataSource.InvalidResponseCodeException) {
            return curr.responseCode
        }
        curr = curr.cause
    }
    return null
}

@OptIn(UnstableApi::class)
private class ResilientStreamErrorPolicy : DefaultLoadErrorHandlingPolicy(5) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val httpCode = findHttpStatusCode(loadErrorInfo.exception)
        if (httpCode != null) {
            return when (httpCode) {
                429, 503, 502, 504, 500, 408 -> {
                    val count = loadErrorInfo.errorCount.coerceAtLeast(1)
                    (count * 700L).coerceAtMost(3_500L)
                }
                403, 404, 410, 416 -> C.TIME_UNSET
                else -> super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    request: PlayerLaunchRequest,
    settings: AppSettings,
    isInPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val app = AwAnimeApp.instance
    val client = app.animeUnityClient
    val historyRepo = app.historyRepository
    val downloadRepo = app.downloadRepository
    val cs = MaterialTheme.colorScheme

    var currentIndex by remember(request) { mutableIntStateOf(request.startIndex) }
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

    // Smart Skip OP/ED (VixCloud native timestamps + AniSkip fallback)
    var skipSegments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var autoSkippedKinds by remember(currentIndex) { mutableStateOf<Set<SkipKind>>(emptySet()) }
    var undoSkipInfo by remember { mutableStateOf<Pair<String, Long>?>(null) }

    // Outro Next-Episode Countdown stile Netflix
    var outroCountdownCancelled by remember(currentIndex) { mutableStateOf(false) }
    var outroCountdownSeconds by remember(currentIndex) { mutableIntStateOf(5) }
    var outroCountdownActive by remember(currentIndex) { mutableStateOf(false) }

    // Controlli UI, Blocco Schermo, Drawer Episodi, Gesture Luminosità/Volume
    var controlsVisible by remember { mutableStateOf(true) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var showUnlockPill by remember { mutableStateOf(false) }
    var episodeDrawerOpen by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    var speedIdx by remember { mutableIntStateOf(1) }
    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Adatta",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Riempi",
    )
    var resizeIdx by remember { mutableIntStateOf(0) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

    // HUD luminosità (sinistra) & volume (destra)
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

    // Blocca landscape + fullscreen immersivo + schermo sempre acceso
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

    // Factory HTTP dinamica (aggiorna Referer/Origin in base al mirror VixCloud attivo)
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

    // ExoPlayer Media3: HLS reale 1080p, LoadControl bilanciato per skip rapidi, ErrorPolicy anti-503/429, AudioFocus
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

    // State sempre aggiornati per evitare chiusure stantie nei DisposableEffect tra un episodio e l'altro
    val latestEpisode by rememberUpdatedState(currentEpisode)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestIsInPip by rememberUpdatedState(isInPipMode)

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

    fun switchEpisode(newIndex: Int) {
        if (newIndex !in request.episodes.indices) return
        persistCurrentProgress()
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
                        switchEpisode(currentIndex + 1)
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
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
                    // Rinnovo automatico singolo di token/stream conservando esattamente il punto raggiunto
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
            persistCurrentProgress()
            exoPlayer.release()
        }
    }

    // Auto-pausa su ON_PAUSE quando NON siamo in PiP e su ON_STOP sempre (es. chiusura finestra PiP)
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !latestIsInPip) {
                persistCurrentProgress()
                exoPlayer.pause()
            } else if (event == Lifecycle.Event.ON_STOP) {
                persistCurrentProgress()
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Carica episodio + estrae timestamp VixCloud + fallback AniSkip asincrono
    LaunchedEffect(currentIndex, retryCounter) {
        isResolving = true
        errorMessage = null
        streamCandidates = emptyList()
        skipSegments = emptyList()
        autoSkippedKinds = emptySet()
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
            val uri = Uri.fromFile(localFile)
            val cand = StreamCandidate(url = uri.toString(), isHls = false, qualityLabel = "OFFLINE")
            streamCandidates = listOf(cand)
            isResolving = false
            exoPlayer.setMediaItem(buildMediaItem(cand))
            exoPlayer.prepare()
            if (pendingSeekMs > 0L) exoPlayer.seekTo(pendingSeekMs)
            exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
            exoPlayer.playWhenReady = true
            if (request.anime.malId > 0) {
                val aniSkip = client.fetchAniSkipSegments(request.anime.malId, ep.number, ep.runtimeSeconds)
                if (aniSkip.isNotEmpty()) skipSegments = aniSkip
            }
            return@LaunchedEffect
        }

        isLocalOfflineStream = false
        runCatching { client.resolveStream(request.anime.id, ep.videoId) }
            .onSuccess { res ->
                val origin = res.referer.trimEnd('/')
                httpDataSourceFactory.setDefaultRequestProperties(
                    mapOf(
                        "Referer" to res.referer,
                        "Origin" to origin,
                    )
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

                // Se VixCloud non aveva timestamp per questo episodio, interroghiamo AniSkip in background
                if (res.skipSegments.isEmpty() && request.anime.malId > 0) {
                    val fallback = client.fetchAniSkipSegments(request.anime.malId, ep.number, ep.runtimeSeconds)
                    if (fallback.isNotEmpty()) {
                        skipSegments = fallback
                    }
                }
            }
            .onFailure { err ->
                isResolving = false
                errorMessage = err.message ?: "Impossibile ottenere lo stream dell'episodio"
            }
    }

    // Segmento attivo nella posizione corrente (Intro / Outro / Recap)
    val activeSkipSegment = remember(positionMs, skipSegments) {
        skipSegments.firstOrNull { it.contains(positionMs) }
    }

    // Trigger Auto-Skip se abilitato nelle Impostazioni
    LaunchedEffect(activeSkipSegment, settings.autoSkipIntro, settings.autoSkipOutro, isPlaying) {
        val seg = activeSkipSegment ?: return@LaunchedEffect
        if (!isPlaying || seg.kind in autoSkippedKinds) return@LaunchedEffect
        val shouldAuto = (seg.kind == SkipKind.INTRO && settings.autoSkipIntro) ||
            (seg.kind == SkipKind.OUTRO && settings.autoSkipOutro)
        if (shouldAuto) {
            autoSkippedKinds = autoSkippedKinds + seg.kind
            skipSegmentNow(seg, wasAuto = true)
        }
    }

    // Trigger Countdown "Prossimo episodio tra 5s" durante l'Outro (o ultimi 15s)
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

    // Loop posizione 400ms + salvataggio cronologia ogni 5s
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

    // Auto-hide controlli (NON nasconde mai mentre l'utente sta trascinando lo Slider!)
    LaunchedEffect(controlsVisible, isPlaying, episodeDrawerOpen, isSeeking) {
        if (!controlsVisible && isSeeking) {
            isSeeking = false
        }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isScreenLocked, episodeDrawerOpen, isInPipMode) {
                detectTapGestures(
                    onTap = {
                        if (isInPipMode) return@detectTapGestures
                        if (isScreenLocked) {
                            showUnlockPill = !showUnlockPill
                        } else if (episodeDrawerOpen) {
                            episodeDrawerOpen = false
                        } else {
                            controlsVisible = !controlsVisible
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!isInPipMode && !isScreenLocked && !episodeDrawerOpen) {
                            if (offset.x < size.width / 2f) {
                                seekBy(-10_000L, "-10s")
                            } else {
                                seekBy(10_000L, "+10s")
                            }
                        }
                    },
                )
            }
            .pointerInput(isScreenLocked, episodeDrawerOpen, isInPipMode) {
                var isLeftSide = true
                detectVerticalDragGestures(
                    onDragStart = { startOffset ->
                        isLeftSide = startOffset.x < size.width / 2f
                        if (!isLeftSide) {
                            volumeFraction = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat()
                        }
                    },
                    onDragEnd = { activeGestureHud = null },
                    onDragCancel = { activeGestureHud = null },
                    onVerticalDrag = { _, dragAmount ->
                        if (isInPipMode || isScreenLocked || episodeDrawerOpen) return@detectVerticalDragGestures
                        val delta = -dragAmount / (size.height * 0.65f)
                        if (isLeftSide) {
                            brightnessLevel = (brightnessLevel + delta).coerceIn(0.02f, 1.0f)
                            activity?.window?.let { win ->
                                val attrs = win.attributes
                                attrs.screenBrightness = brightnessLevel
                                win.attributes = attrs
                            }
                            activeGestureHud = true to brightnessLevel
                        } else {
                            volumeFraction = (volumeFraction + delta).coerceIn(0f, 1f)
                            val targetVol = (volumeFraction * maxVolume).toInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            activeGestureHud = false to volumeFraction
                        }
                    },
                )
            },
    ) {
        // Video Surface nativa Media3
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

        // Spinner caricamento / buffering
        if (isResolving || isBuffering) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = cs.primary)
                    if (isResolving && !isInPipMode) {
                        Spacer(Modifier.height(10.dp))
                        Text("Risoluzione stream 1080p HLS...", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        // HUD verticale Luminosità / Volume
        if (!isInPipMode) {
            activeGestureHud?.let { (isBrightness, fraction) ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xCC11131C),
                    modifier = Modifier
                        .align(if (isBrightness) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 36.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
                    ) {
                        Icon(
                            imageVector = if (isBrightness) Icons.Rounded.Brightness6 else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(110.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fraction.coerceIn(0.02f, 1f))
                                    .clip(CircleShape)
                                    .background(cs.primary),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("${(fraction * 100).toInt()}%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Badge feedback rapido (-10s / +10s)
        if (!isInPipMode) {
            seekFeedback?.let { fb ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(Color(0xCC0B0D14))
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(fb, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // Pillola "Annulla Auto-Skip"
        AnimatedVisibility(
            visible = undoSkipInfo != null && !isScreenLocked && !isInPipMode,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = if (controlsVisible) 92.dp else 28.dp),
        ) {
            undoSkipInfo?.let { (label, origMs) ->
                Surface(
                    shape = CircleShape,
                    color = Color(0xDD181B28),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                exoPlayer.seekTo(origMs)
                                positionMs = origMs
                                undoSkipInfo = null
                            },
                        ) {
                            Text("Annulla", color = cs.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Bottone Fluttuante Stile Netflix "Salta Sigla" / "Salta Outro"
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
                .padding(end = 28.dp, bottom = if (controlsVisible) 96.dp else 32.dp),
        ) {
            activeSkipSegment?.let { seg ->
                Button(
                    onClick = { skipSegmentNow(seg, wasAuto = false) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF0E1018),
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Rounded.FastForward, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = seg.kind.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
        }

        // Card Stile Netflix "Prossimo Episodio tra 5s" durante l'Outro
        AnimatedVisibility(
            visible = outroCountdownActive && hasNextEpisode && !isScreenLocked && !episodeDrawerOpen && !isInPipMode,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut() + slideOutHorizontally { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = if (controlsVisible) 96.dp else 28.dp),
        ) {
            val nextEp = request.episodes.getOrNull(currentIndex + 1)
            if (nextEp != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xEB151826),
                    shadowElevation = 10.dp,
                ) {
                    Column(modifier = Modifier.padding(16.dp).width(240.dp)) {
                        Text(
                            text = "Prossimo episodio tra ${outroCountdownSeconds}s",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Episodio ${nextEp.number}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (6 - outroCountdownSeconds).coerceIn(1, 5) / 5f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = cs.primary,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    outroCountdownCancelled = true
                                    outroCountdownActive = false
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color.White.copy(alpha = 0.14f),
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier.weight(1f).height(34.dp),
                            ) {
                                Text("Annulla", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { switchEpisode(currentIndex + 1) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.weight(1f).height(34.dp),
                            ) {
                                Text("Guarda ora", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // Pillola "Sblocca schermo"
        AnimatedVisibility(
            visible = isScreenLocked && showUnlockPill && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
        ) {
            Button(
                onClick = {
                    isScreenLocked = false
                    showUnlockPill = false
                    controlsVisible = true
                },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xCC151826),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sblocca schermo", fontWeight = FontWeight.Bold)
            }
        }

        // Schermata Errore
        if (!isInPipMode) {
            errorMessage?.let { err ->
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xEE141824),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(err, color = Color(0xFFFF6B81), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    persistCurrentProgress()
                                    onClose()
                                },
                                shape = CircleShape,
                            ) { Text("Chiudi") }
                            Button(
                                onClick = {
                                    pendingSeekMs = positionMs.coerceAtLeast(pendingSeekMs)
                                    hasAutoRefreshedToken = false
                                    retryCounter++
                                },
                                shape = CircleShape,
                            ) { Text("Riprova") }
                        }
                    }
                }
            }
        }

        // OVERLAY CONTROLLI CINEMA
        AnimatedVisibility(
            visible = controlsVisible && !isScreenLocked && !episodeDrawerOpen && !isInPipMode && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xBB000000), Color(0x28000000), Color(0xCC000000)),
                        )
                    ),
            ) {
                // TOP BAR
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            persistCurrentProgress()
                            onClose()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = Color.White)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = request.anime.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Episodio ${currentEpisode.number}",
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 12.sp,
                            )
                            BadgePill(
                                text = qualityBadge,
                                backgroundColor = if (qualityBadge == "OFFLINE") BadgeGreen else cs.primary,
                                contentColor = if (qualityBadge == "OFFLINE") Color.White else cs.onPrimary,
                            )
                            if (skipSegments.isNotEmpty()) {
                                BadgePill(
                                    text = "SMART SKIP",
                                    backgroundColor = Color.White.copy(alpha = 0.18f),
                                    contentColor = Color.White,
                                )
                            }
                        }
                    }

                    // Episodi Drawer
                    if (request.episodes.size > 1) {
                        PlayerTopPill(
                            icon = Icons.AutoMirrored.Rounded.List,
                            label = "Episodi",
                            onClick = { episodeDrawerOpen = true },
                        )
                        Spacer(Modifier.width(8.dp))
                    }

                    // Velocità
                    PlayerTopPill(
                        icon = Icons.Rounded.Speed,
                        label = "${speeds[speedIdx]}x",
                        onClick = {
                            speedIdx = (speedIdx + 1) % speeds.size
                            exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
                        },
                    )
                    Spacer(Modifier.width(8.dp))

                    // Aspect Ratio
                    PlayerTopPill(
                        icon = Icons.Rounded.AspectRatio,
                        label = resizeModes[resizeIdx].second,
                        onClick = { resizeIdx = (resizeIdx + 1) % resizeModes.size },
                    )
                    Spacer(Modifier.width(6.dp))

                    // Picture-in-Picture (Android 8+)
                    IconButton(onClick = onEnterPip) {
                        Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = Color.White)
                    }

                    // Lucchetto anti-tocchi
                    IconButton(
                        onClick = {
                            isScreenLocked = true
                            controlsVisible = false
                            showUnlockPill = true
                        },
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = "Blocca schermo", tint = Color.White)
                    }
                }

                // CENTER CONTROLS
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { switchEpisode(currentIndex - 1) }, enabled = currentIndex > 0) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Precedente",
                            tint = if (currentIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    IconButton(onClick = { seekBy(-10_000L, "-10s") }) {
                        Icon(Icons.Rounded.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    IconButton(
                        onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(cs.primary.copy(alpha = 0.92f)),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pausa" else "Play",
                            tint = cs.onPrimary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    IconButton(onClick = { seekBy(10_000L, "+10s") }) {
                        Icon(Icons.Rounded.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(38.dp))
                    }
                    IconButton(onClick = { switchEpisode(currentIndex + 1) }, enabled = hasNextEpisode) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Successivo",
                            tint = if (hasNextEpisode) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }

                // BOTTOM BAR
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatTimeMs(if (isSeeking) (sliderPosition * durationMs).toLong() else positionMs)} / ${formatTimeMs(durationMs)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )

                        // Fallback +85s mostrato SOLO se non esistono timestamp reali per l'episodio
                        if (skipSegments.isEmpty()) {
                            PlayerTopPill(
                                icon = Icons.Rounded.FastForward,
                                label = "+85s",
                                onClick = { seekBy(85_000L, "+85s") },
                            )
                        }
                    }

                    Slider(
                        value = sliderPosition,
                        onValueChange = { value ->
                            isSeeking = true
                            sliderPosition = value
                        },
                        onValueChangeFinished = {
                            if (durationMs > 1_000L) {
                                val target = (sliderPosition * durationMs).toLong().coerceIn(0L, durationMs - 1_000L)
                                exoPlayer.seekTo(target)
                                positionMs = target
                            }
                            isSeeking = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = cs.primary,
                            activeTrackColor = cs.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // DRAWER EPISODI IN-PLAYER (slide da destra)
        AnimatedVisibility(
            visible = episodeDrawerOpen && !isInPipMode,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            val histEntry = historyRepo.getEntry(request.anime.uniqueKey)
            Surface(
                color = Color(0xF0121420),
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Episodi (${request.episodes.size})",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { episodeDrawerOpen = false }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(request.episodes) { idx, ep ->
                            val isCurrent = idx == currentIndex
                            val completed = histEntry?.episodesProgress?.get(ep.number)?.completed == true
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isCurrent) cs.primaryContainer else Color.White.copy(alpha = 0.06f))
                                    .clickable { switchEpisode(idx) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = "Episodio ${ep.number}",
                                    color = if (isCurrent) cs.onPrimaryContainer else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (completed) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = "Visto",
                                        tint = BadgeGreen,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTopPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x77000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
