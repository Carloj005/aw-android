package com.carloj005.awanime.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.carloj005.awanime.data.integrations.SuiteTvIntegration
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.StreamCandidate
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.ui.PlayerLaunchRequest
import com.carloj005.awanime.ui.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class PipCommand {
    TOGGLE_PLAY,
    REWIND_10,
    FORWARD_10,
}

private enum class VerticalGestureZone {
    NONE,
    BRIGHTNESS,
    VOLUME,
}

private const val GESTURE_SAFE_TOP_FRACTION = 0.18f
private const val GESTURE_SAFE_BOTTOM_FRACTION = 0.82f
private const val BRIGHTNESS_MIN_X_FRACTION = 0.10f
private const val BRIGHTNESS_MAX_X_FRACTION = 0.35f
private const val VOLUME_MIN_X_FRACTION = 0.65f
private const val VOLUME_MAX_X_FRACTION = 0.90f
private const val DOUBLE_TAP_LEFT_FRACTION = 0.38f
private const val DOUBLE_TAP_RIGHT_FRACTION = 0.62f

private const val SEEK_DELTA_MS = 10_000L
private const val FALLBACK_OP_SKIP_MS = 85_000L
private const val OUTRO_COUNTDOWN_WINDOW_MS = 15_000L
private const val CONTROLS_AUTO_HIDE_MS = 3_500L
private const val PROGRESS_POLL_INTERVAL_MS = 400L

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
    pipCommands: Flow<PipCommand> = emptyFlow(),
    onPlayingStateChanged: (Boolean) -> Unit = {},
    onEnterPip: () -> Unit = {},
    onOpenSuiteTv: (com.carloj005.awanime.data.model.AnimeItem) -> Unit = {},
    onClose: () -> Unit,
    playerVm: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(request) {
        playerVm.initializeRequest(request)
    }

    val session by playerVm.state.collectAsStateWithLifecycle()
    val currentEpisode = playerVm.currentEpisode(request)
    val hasNextEpisode = session.currentIndex + 1 < request.episodes.size

    var isBuffering by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var showUnlockPill by remember { mutableStateOf(false) }
    var episodeDrawerOpen by remember { mutableStateOf(false) }

    var outroCountdownCancelled by remember(session.currentIndex) { mutableStateOf(false) }
    var outroCountdownSeconds by remember(session.currentIndex) { mutableIntStateOf(5) }
    var outroCountdownActive by remember(session.currentIndex) { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }

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

    var brightnessLevel by remember {
        mutableFloatStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.55f)
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volumeFraction by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat())
    }
    var activeGestureHud by remember { mutableStateOf<Pair<Boolean, Float>?>(null) }

    BindImmersivePlayerWindow(activity = activity, isInPipMode = isInPipMode)

    val httpDataSourceFactory = remember(playerVm.mediaHttpClient) {
        OkHttpDataSource.Factory(playerVm.mediaHttpClient)
            .setUserAgent(AnimeUnityClient.USER_AGENT)
            .setDefaultRequestProperties(
                mapOf("Referer" to "https://vixcloud.cc/", "Origin" to "https://vixcloud.cc")
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

    val latestRequest by rememberUpdatedState(request)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestOnPlayingChanged by rememberUpdatedState(onPlayingStateChanged)

    fun persistNow(forceCompleted: Boolean = false) {
        val dur = exoPlayer.duration.takeIf { it > 0L } ?: latestDurationMs
        playerVm.persistProgress(
            request = latestRequest,
            positionMs = exoPlayer.currentPosition,
            durationMs = dur,
            forceCompleted = forceCompleted,
        )
    }

    fun switchToEpisode(newIndex: Int) {
        val dur = exoPlayer.duration.takeIf { it > 0L } ?: latestDurationMs
        if (playerVm.switchEpisode(newIndex, latestRequest, exoPlayer.currentPosition, dur)) {
            exoPlayer.stop()
            episodeDrawerOpen = false
            outroCountdownCancelled = false
            outroCountdownActive = false
        }
    }

    fun skipSegmentNow(segment: SkipSegment, wasAuto: Boolean = false) {
        val fromPos = exoPlayer.currentPosition.coerceAtLeast(0L)
        if (segment.kind == SkipKind.OUTRO && hasNextEpisode && durationMs > 0L && segment.endMs >= durationMs - 5_000L) {
            switchToEpisode(session.currentIndex + 1)
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
            playerVm.recordAutoSkip(segment.kind, label, fromPos)
        } else {
            seekFeedback = segment.kind.label
        }
    }

    // Bind newly resolved stream candidate onto ExoPlayer
    LaunchedEffect(session.preparedGeneration) {
        val cand = session.activeCandidate ?: return@LaunchedEffect
        if (!session.isLocalOfflineStream) {
            val origin = session.referer.trimEnd('/')
            httpDataSourceFactory.setDefaultRequestProperties(
                mapOf("Referer" to session.referer, "Origin" to origin)
            )
        }
        exoPlayer.setMediaItem(buildMediaItem(cand))
        exoPlayer.prepare()
        if (session.pendingSeekMs > 0L) exoPlayer.seekTo(session.pendingSeekMs)
        exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
                if (state == Player.STATE_ENDED) {
                    persistNow(forceCompleted = true)
                    val curIdx = playerVm.state.value.currentIndex
                    if (curIdx + 1 < latestRequest.episodes.size && !outroCountdownCancelled) {
                        switchToEpisode(curIdx + 1)
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                latestOnPlayingChanged(playing)
                if (!playing) persistNow()
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                val resumePos = maxOf(
                    exoPlayer.currentPosition,
                    positionMs,
                    playerVm.state.value.pendingSeekMs,
                )
                playerVm.onPlaybackError(
                    request = latestRequest,
                    httpCode = findHttpStatusCode(error),
                    errorCodeName = error.errorCodeName,
                    resumePositionMs = resumePos,
                )
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            latestOnPlayingChanged(false)
            persistNow()
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                persistNow()
            } else if (event == Lifecycle.Event.ON_STOP) {
                persistNow()
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pipCommands, exoPlayer) {
        pipCommands.collect { cmd ->
            when (cmd) {
                PipCommand.TOGGLE_PLAY -> if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                PipCommand.REWIND_10 -> {
                    val target = (exoPlayer.currentPosition - SEEK_DELTA_MS).coerceAtLeast(0L)
                    exoPlayer.seekTo(target)
                    positionMs = target
                }
                PipCommand.FORWARD_10 -> {
                    val maxPos = if (latestDurationMs > 1_000L) latestDurationMs - 1_000L else Long.MAX_VALUE
                    val target = (exoPlayer.currentPosition + SEEK_DELTA_MS).coerceIn(0L, maxPos)
                    exoPlayer.seekTo(target)
                    positionMs = target
                }
            }
        }
    }

    LaunchedEffect(durationMs, session.currentIndex, session.skipFetchedWithDuration, session.isResolving) {
        playerVm.fetchSkipFallbackWithRealDuration(request, durationMs)
    }

    val activeSkipSegment = remember(positionMs, session.skipSegments, session.dismissedSkipKinds) {
        session.skipSegments.firstOrNull { it.kind !in session.dismissedSkipKinds && it.contains(positionMs) }
    }

    LaunchedEffect(activeSkipSegment, settings.autoSkipIntro, settings.autoSkipOutro, isPlaying, currentEpisode.number) {
        val seg = activeSkipSegment ?: return@LaunchedEffect
        if (!isPlaying || seg.kind in session.autoSkippedKinds) return@LaunchedEffect
        val isEpisodeOne = currentEpisode.number.trim() == "1"
        val allowIntroAuto = settings.autoSkipIntro && (seg.verified || !isEpisodeOne)
        val shouldAuto = (seg.kind == SkipKind.INTRO && allowIntroAuto) ||
            (seg.kind == SkipKind.OUTRO && settings.autoSkipOutro)
        if (shouldAuto) {
            skipSegmentNow(seg, wasAuto = true)
        }
    }

    val inOutroForNextEp = hasNextEpisode && !outroCountdownCancelled && (
        activeSkipSegment?.kind == SkipKind.OUTRO ||
            (durationMs > 60_000L && positionMs >= durationMs - OUTRO_COUNTDOWN_WINDOW_MS)
        )
    LaunchedEffect(inOutroForNextEp, isPlaying, session.currentIndex) {
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
            switchToEpisode(session.currentIndex + 1)
        } else {
            outroCountdownActive = false
        }
    }

    LaunchedEffect(exoPlayer, session.currentIndex) {
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
            if (tick % 12 == 0 && exoPlayer.isPlaying) persistNow()
            delay(PROGRESS_POLL_INTERVAL_MS)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, episodeDrawerOpen, isSeeking) {
        if (!controlsVisible && isSeeking) isSeeking = false
        if (controlsVisible && isPlaying && !episodeDrawerOpen && !isSeeking) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(showUnlockPill) {
        if (showUnlockPill) {
            delay(2_500L)
            showUnlockPill = false
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(750L)
            seekFeedback = null
        }
    }
    LaunchedEffect(session.undoSkipInfo) {
        if (session.undoSkipInfo != null) {
            delay(4_500L)
            playerVm.clearUndoSkip()
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
                        when {
                            isScreenLocked -> showUnlockPill = !showUnlockPill
                            episodeDrawerOpen -> episodeDrawerOpen = false
                            else -> controlsVisible = !controlsVisible
                        }
                    },
                    onDoubleTap = { offset ->
                        if (!isInPipMode && !isScreenLocked && !episodeDrawerOpen) {
                            when {
                                offset.x < size.width * DOUBLE_TAP_LEFT_FRACTION -> seekBy(-SEEK_DELTA_MS, "-10s")
                                offset.x > size.width * DOUBLE_TAP_RIGHT_FRACTION -> seekBy(SEEK_DELTA_MS, "+10s")
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
                        val inSafeBand = startOffset.y in (h * GESTURE_SAFE_TOP_FRACTION)..(h * GESTURE_SAFE_BOTTOM_FRACTION)
                        activeZone = when {
                            !inSafeBand -> VerticalGestureZone.NONE
                            startOffset.x in (w * BRIGHTNESS_MIN_X_FRACTION)..(w * BRIGHTNESS_MAX_X_FRACTION) ->
                                VerticalGestureZone.BRIGHTNESS
                            startOffset.x in (w * VOLUME_MIN_X_FRACTION)..(w * VOLUME_MAX_X_FRACTION) -> {
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

        if (session.isResolving || isBuffering) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = cs.primary)
                    if (session.isResolving && !isInPipMode) {
                        Spacer(Modifier.height(10.dp))
                        Text("Risoluzione stream 1080p HLS...", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        if (!isInPipMode) {
            VerticalGestureHud(gestureHud = activeGestureHud)
            SeekFeedbackPill(feedback = seekFeedback)
        }

        UndoSkipBanner(
            visible = session.undoSkipInfo != null && !isScreenLocked && !isInPipMode,
            undoSkipInfo = session.undoSkipInfo,
            controlsVisible = controlsVisible,
            onUndo = { origMs ->
                exoPlayer.seekTo(origMs)
                positionMs = origMs
                playerVm.clearUndoSkip()
            },
        )

        val showFloatingSkip = activeSkipSegment != null &&
            !isScreenLocked &&
            !episodeDrawerOpen &&
            !outroCountdownActive &&
            !isInPipMode &&
            session.errorMessage == null

        FloatingSkipButton(
            visible = showFloatingSkip,
            segment = activeSkipSegment,
            controlsVisible = controlsVisible,
            onDismiss = { playerVm.dismissSkipKind(it.kind) },
            onSkip = { skipSegmentNow(it, wasAuto = false) },
        )

        NextEpisodeCountdownCard(
            visible = outroCountdownActive && hasNextEpisode && !isScreenLocked && !episodeDrawerOpen && !isInPipMode,
            nextEpisode = request.episodes.getOrNull(session.currentIndex + 1),
            countdownSeconds = outroCountdownSeconds,
            controlsVisible = controlsVisible,
            showSuiteTvAction = settings.suiteTvQuickAction,
            onOpenSuiteTv = {
                outroCountdownCancelled = true
                outroCountdownActive = false
                persistNow(forceCompleted = true)
                onOpenSuiteTv(request.anime)
            },
            onCancel = {
                outroCountdownCancelled = true
                outroCountdownActive = false
            },
            onWatchNow = { switchToEpisode(session.currentIndex + 1) },
        )

        UnlockScreenPill(
            visible = isScreenLocked && showUnlockPill && !isInPipMode,
            onUnlock = {
                isScreenLocked = false
                showUnlockPill = false
                controlsVisible = true
            },
        )

        if (!isInPipMode) {
            PlayerErrorCard(
                errorMessage = session.errorMessage,
                onClose = {
                    persistNow()
                    onClose()
                },
                onRetry = { playerVm.retryFromError(request, positionMs) },
            )
        }

        PlayerControlsOverlay(
            visible = controlsVisible && !isScreenLocked && !episodeDrawerOpen && !isInPipMode && session.errorMessage == null,
            animeTitle = request.anime.title,
            episodeNumber = currentEpisode.number,
            qualityBadge = session.qualityBadge,
            hasSmartSkip = session.skipSegments.isNotEmpty(),
            hasMultipleEpisodes = request.episodes.size > 1,
            hasPreviousEpisode = session.currentIndex > 0,
            hasNextEpisode = hasNextEpisode,
            isPlaying = isPlaying,
            speedLabel = "${speeds[speedIdx]}x",
            resizeLabel = resizeModes[resizeIdx].second,
            positionMs = positionMs,
            durationMs = durationMs,
            isSeeking = isSeeking,
            sliderPosition = sliderPosition,
            onBack = {
                persistNow()
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
            onPreviousEpisode = { switchToEpisode(session.currentIndex - 1) },
            onRewind10 = { seekBy(-SEEK_DELTA_MS, "-10s") },
            onTogglePlay = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
            onForward10 = { seekBy(SEEK_DELTA_MS, "+10s") },
            onNextEpisode = { switchToEpisode(session.currentIndex + 1) },
            onFallbackSkip85 = { seekBy(FALLBACK_OP_SKIP_MS, "+85s") },
            onSliderChange = { value ->
                isSeeking = true
                sliderPosition = value
            },
            onSliderFinished = {
                if (durationMs > 1_000L) {
                    val target = (sliderPosition * durationMs).toLong().coerceIn(0L, durationMs - 1_000L)
                    exoPlayer.seekTo(target)
                    positionMs = target
                }
                isSeeking = false
            },
        )

        PlayerEpisodeDrawer(
            visible = episodeDrawerOpen && !isInPipMode,
            episodes = request.episodes,
            currentIndex = session.currentIndex,
            historyEntry = playerVm.getHistoryEntry(request.anime.uniqueKey),
            onClose = { episodeDrawerOpen = false },
            onSelectEpisode = { idx -> switchToEpisode(idx) },
        )
    }
}

@Composable
private fun BindImmersivePlayerWindow(
    activity: Activity?,
    isInPipMode: Boolean,
) {
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
}
