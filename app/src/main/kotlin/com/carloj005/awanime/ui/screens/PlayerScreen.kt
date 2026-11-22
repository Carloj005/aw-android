package com.carloj005.awanime.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.media3.common.MediaItem
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
import com.carloj005.awanime.AwAnimeApp
import com.carloj005.awanime.data.model.formatTimeMs
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.ui.PlayerLaunchRequest
import com.carloj005.awanime.ui.components.BadgePill
import com.carloj005.awanime.ui.theme.AccentCyan
import com.carloj005.awanime.ui.theme.AccentViolet
import com.carloj005.awanime.ui.theme.BadgeGreen
import com.carloj005.awanime.ui.theme.ErrorRed
import com.carloj005.awanime.ui.theme.TextPrimary
import com.carloj005.awanime.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    request: PlayerLaunchRequest,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val app = AwAnimeApp.instance
    val client = app.animeUnityClient
    val historyRepo = app.historyRepository
    val downloadRepo = app.downloadRepository

    var currentIndex by remember(request) { mutableIntStateOf(request.startIndex) }
    var pendingSeekMs by remember(request) { mutableLongStateOf(request.startPositionMs) }
    val currentEpisode = request.episodes[currentIndex.coerceIn(0, request.episodes.lastIndex)]

    var isResolving by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var qualityBadge by remember { mutableStateOf("1080p") }
    var streamCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var candidateIdx by remember { mutableIntStateOf(0) }
    var retryCounter by remember { mutableIntStateOf(0) }

    var controlsVisible by remember { mutableStateOf(true) }
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

    // Blocca orientamento landscape + modalità immersiva fullscreen + mantieni schermo acceso
    DisposableEffect(activity) {
        val prevOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = prevOrientation
        }
    }

    // Istanza ExoPlayer con OkHttp + DoH + Referer VixCloud + LoadControl anti-stallo skip rapidi + qualità massima
    val exoPlayer = remember(context) {
        val httpFactory = OkHttpDataSource.Factory(client.mediaOkHttpClient)
            .setUserAgent(AnimeUnityClient.USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://vixcloud.cc/",
                    "Origin" to "https://vixcloud.cc",
                )
            )
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                60_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs (avvio ultra rapido dopo skip)
                3_000,  // bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(30_000, true) // 30s di back-buffer per skip indietro istantanei
            .build()

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    .setMaxVideoSize(3840, 2160)
            )
        }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .build()
    }

    fun persistCurrentProgress() {
        val dur = exoPlayer.duration.takeIf { it > 0L } ?: durationMs
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        if (dur > 0L || pos > 2_000L) {
            historyRepo.updateProgress(
                anime = request.anime,
                episodeNum = currentEpisode.number,
                videoId = currentEpisode.videoId,
                positionMs = pos,
                durationMs = dur,
            )
        }
    }

    // Listener stato ExoPlayer + fallback automatico tra i candidati stream
    DisposableEffect(exoPlayer, currentIndex, streamCandidates, candidateIdx) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                }
                if (state == Player.STATE_ENDED) {
                    persistCurrentProgress()
                    if (currentIndex + 1 < request.episodes.size) {
                        currentIndex += 1
                        pendingSeekMs = 0L
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) persistCurrentProgress()
            }

            override fun onPlayerError(error: PlaybackException) {
                val nextCand = candidateIdx + 1
                if (nextCand < streamCandidates.size) {
                    candidateIdx = nextCand
                    val resumePos = exoPlayer.currentPosition.coerceAtLeast(pendingSeekMs)
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(streamCandidates[nextCand])))
                    exoPlayer.prepare()
                    if (resumePos > 0L) exoPlayer.seekTo(resumePos)
                    exoPlayer.playWhenReady = true
                } else {
                    errorMessage = "Errore riproduzione stream (${error.errorCodeName}). Tocca Riprova."
                    isBuffering = false
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            persistCurrentProgress()
            exoPlayer.release()
        }
    }

    // Caricamento episodio (file offline locale prioritario -> altrimenti risoluzione VixCloud 1080p)
    LaunchedEffect(currentIndex, retryCounter) {
        isResolving = true
        errorMessage = null
        streamCandidates = emptyList()
        candidateIdx = 0
        exoPlayer.stop()

        val ep = request.episodes[currentIndex.coerceIn(0, request.episodes.lastIndex)]
        val localFile = downloadRepo.getLocalFileIfReady(request.anime.id, request.anime.isDub, ep.number)
        if (localFile != null) {
            qualityBadge = "OFFLINE"
            val uri = Uri.fromFile(localFile)
            streamCandidates = listOf(uri.toString())
            isResolving = false
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            if (pendingSeekMs > 0L) exoPlayer.seekTo(pendingSeekMs)
            exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
            exoPlayer.playWhenReady = true
            return@LaunchedEffect
        }

        runCatching { client.resolveStream(request.anime.id, ep.videoId) }
            .onSuccess { res ->
                qualityBadge = res.qualityLabel
                streamCandidates = res.candidates
                candidateIdx = 0
                isResolving = false
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(res.candidates.first())))
                exoPlayer.prepare()
                if (pendingSeekMs > 0L) exoPlayer.seekTo(pendingSeekMs)
                exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
                exoPlayer.playWhenReady = true
            }
            .onFailure { err ->
                isResolving = false
                errorMessage = err.message ?: "Impossibile ottenere lo stream dell'episodio"
            }
    }

    // Aggiornamento posizione ogni 500ms + salvataggio cronologia ogni 5s
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
            if (tick % 10 == 0 && exoPlayer.isPlaying) {
                persistCurrentProgress()
            }
            delay(500L)
        }
    }

    // Auto-hide controlli dopo 3.5 secondi
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3500L)
            controlsVisible = false
        }
    }

    // Auto-hide feedback doppio tap
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(750L)
            seekFeedback = null
        }
    }

    fun switchEpisode(newIndex: Int) {
        if (newIndex !in request.episodes.indices) return
        persistCurrentProgress()
        val nextEp = request.episodes[newIndex]
        val saved = historyRepo.getEpisodeProgress(request.anime.uniqueKey, nextEp.number)
        pendingSeekMs = if (saved != null && !saved.completed) saved.positionMs else 0L
        currentIndex = newIndex
    }

    fun seekBy(deltaMs: Long, label: String) {
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(exoPlayer.currentPosition + deltaMs))
        exoPlayer.seekTo(target)
        positionMs = target
        seekFeedback = label
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) {
                            seekBy(-10_000L, "-10s")
                        } else {
                            seekBy(10_000L, "+10s")
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
            update = { view ->
                view.resizeMode = resizeModes[resizeIdx].first
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Spinner caricamento / buffering
        if (isResolving || isBuffering) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentViolet)
                    if (isResolving) {
                        Spacer(Modifier.height(10.dp))
                        Text("Risoluzione stream 1080p...", color = TextPrimary, fontSize = 13.sp)
                    }
                }
            }
        }

        // Badge feedback rapido (-10s / +10s / +85s)
        seekFeedback?.let { fb ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCC0B0D14))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(fb, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }

        // Schermata errore con Riprova
        errorMessage?.let { err ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6141824))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(err, color = ErrorRed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            persistCurrentProgress()
                            onClose()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B3248)),
                    ) {
                        Text("Chiudi")
                    }
                    Button(
                        onClick = { retryCounter++ },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                    ) {
                        Text("Riprova")
                    }
                }
            }
        }

        // Overlay Controlli Cinema
        AnimatedVisibility(
            visible = controlsVisible && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xBB000000), Color(0x33000000), Color(0xCC000000)),
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
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Esci dal player",
                            tint = Color.White,
                        )
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
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                            BadgePill(
                                text = qualityBadge,
                                backgroundColor = if (qualityBadge == "OFFLINE") BadgeGreen else AccentViolet,
                            )
                        }
                    }

                    // Velocità riproduzione
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x66000000))
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    speedIdx = (speedIdx + 1) % speeds.size
                                    exoPlayer.playbackParameters = PlaybackParameters(speeds[speedIdx])
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${speeds[speedIdx]}x", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(8.dp))

                    // Aspect Ratio (Adatta / Zoom / Riempi)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x66000000))
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    resizeIdx = (resizeIdx + 1) % resizeModes.size
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.AspectRatio, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(resizeModes[resizeIdx].second, color = Color.White, fontSize = 12.sp)
                    }
                }

                // CENTER CONTROLS (Prev, -10s, Play/Pause, +10s, Next)
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { switchEpisode(currentIndex - 1) },
                        enabled = currentIndex > 0,
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Episodio precedente",
                            tint = if (currentIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(34.dp),
                        )
                    }

                    IconButton(onClick = { seekBy(-10_000L, "-10s") }) {
                        Icon(
                            Icons.Rounded.Replay10,
                            contentDescription = "-10 secondi",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(AccentViolet.copy(alpha = 0.9f)),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pausa" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp),
                        )
                    }

                    IconButton(onClick = { seekBy(10_000L, "+10s") }) {
                        Icon(
                            Icons.Rounded.Forward10,
                            contentDescription = "+10 secondi",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }

                    IconButton(
                        onClick = { switchEpisode(currentIndex + 1) },
                        enabled = currentIndex + 1 < request.episodes.size,
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Prossimo episodio",
                            tint = if (currentIndex + 1 < request.episodes.size) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }

                // BOTTOM BAR (+85s Salta Sigla + Seek Slider + Timestamps)
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

                        // Pulsante rapido Salta Sigla (+85s)
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(AccentViolet.copy(alpha = 0.85f))
                                .pointerInput(Unit) {
                                    detectTapGestures { seekBy(85_000L, "+85s") }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("+85s Salta Sigla", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Slider(
                        value = sliderPosition,
                        onValueChange = { value ->
                            isSeeking = true
                            sliderPosition = value
                        },
                        onValueChangeFinished = {
                            if (durationMs > 0L) {
                                val target = (sliderPosition * durationMs).toLong()
                                exoPlayer.seekTo(target)
                                positionMs = target
                            }
                            isSeeking = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = AccentCyan,
                            activeTrackColor = AccentViolet,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
