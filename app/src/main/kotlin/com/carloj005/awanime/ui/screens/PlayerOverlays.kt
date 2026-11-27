package com.carloj005.awanime.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.formatTimeMs
import com.carloj005.awanime.ui.components.BadgePill
import com.carloj005.awanime.ui.theme.BadgeGreen

@Composable
internal fun BoxScope.VerticalGestureHud(
    gestureHud: Pair<Boolean, Float>?,
) {
    val (isBrightness, fraction) = gestureHud ?: return
    val cs = MaterialTheme.colorScheme
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

@Composable
internal fun BoxScope.SeekFeedbackPill(feedback: String?) {
    if (feedback == null) return
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .clip(CircleShape)
            .background(Color(0xCC0B0D14))
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(feedback, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun BoxScope.UndoSkipBanner(
    visible: Boolean,
    undoSkipInfo: Pair<String, Long>?,
    controlsVisible: Boolean,
    onUndo: (Long) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 24.dp, bottom = if (controlsVisible) 92.dp else 28.dp),
    ) {
        undoSkipInfo?.let { (label, origMs) ->
            Surface(shape = CircleShape, color = Color(0xDD181B28)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onUndo(origMs) }) {
                        Text("Annulla", color = cs.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.FloatingSkipButton(
    visible: Boolean,
    segment: SkipSegment?,
    controlsVisible: Boolean,
    onDismiss: (SkipSegment) -> Unit,
    onSkip: (SkipSegment) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInHorizontally { it / 2 },
        exit = fadeOut() + slideOutHorizontally { it / 2 },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 24.dp, bottom = if (controlsVisible) 96.dp else 32.dp),
    ) {
        segment?.let { seg ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { onDismiss(seg) },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xBB151826)),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Nascondi",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Button(
                    onClick = { onSkip(seg) },
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
                    Text(text = seg.kind.label, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.NextEpisodeCountdownCard(
    visible: Boolean,
    nextEpisode: EpisodeItem?,
    countdownSeconds: Int,
    controlsVisible: Boolean,
    showSuiteTvAction: Boolean = false,
    onOpenSuiteTv: () -> Unit = {},
    onCancel: () -> Unit,
    onWatchNow: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = visible && nextEpisode != null,
        enter = fadeIn() + slideInHorizontally { it / 2 },
        exit = fadeOut() + slideOutHorizontally { it / 2 },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 24.dp, bottom = if (controlsVisible) 96.dp else 28.dp),
    ) {
        nextEpisode?.let { nextEp ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xEB151826),
                shadowElevation = 10.dp,
            ) {
                Column(modifier = Modifier.padding(16.dp).width(240.dp)) {
                    Text(
                        text = "Prossimo episodio tra ${countdownSeconds}s",
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
                        progress = { (6 - countdownSeconds).coerceIn(1, 5) / 5f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = cs.primary,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = onCancel,
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
                            onClick = onWatchNow,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.weight(1f).height(34.dp),
                        ) {
                            Text("Guarda ora", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (showSuiteTvAction) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = onOpenSuiteTv,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.fillMaxWidth().height(30.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tv,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Segna su Suite TV",
                                color = cs.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.UnlockScreenPill(
    visible: Boolean,
    onUnlock: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 24.dp),
    ) {
        Button(
            onClick = onUnlock,
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
}

@Composable
internal fun BoxScope.PlayerErrorCard(
    errorMessage: String?,
    onClose: () -> Unit,
    onRetry: () -> Unit,
) {
    val err = errorMessage ?: return
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
                FilledTonalButton(onClick = onClose, shape = CircleShape) { Text("Chiudi") }
                Button(onClick = onRetry, shape = CircleShape) { Text("Riprova") }
            }
        }
    }
}

@Composable
internal fun PlayerControlsOverlay(
    visible: Boolean,
    animeTitle: String,
    episodeNumber: String,
    qualityBadge: String,
    hasSmartSkip: Boolean,
    hasMultipleEpisodes: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    isPlaying: Boolean,
    speedLabel: String,
    resizeLabel: String,
    positionMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    sliderPosition: Float,
    onBack: () -> Unit,
    onOpenEpisodeDrawer: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleResize: () -> Unit,
    onEnterPip: () -> Unit,
    onLockScreen: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onRewind10: () -> Unit,
    onTogglePlay: () -> Unit,
    onForward10: () -> Unit,
    onNextEpisode: () -> Unit,
    onFallbackSkip85: () -> Unit,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
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
            PlayerTopBar(
                animeTitle = animeTitle,
                episodeNumber = episodeNumber,
                qualityBadge = qualityBadge,
                hasSmartSkip = hasSmartSkip,
                hasMultipleEpisodes = hasMultipleEpisodes,
                speedLabel = speedLabel,
                resizeLabel = resizeLabel,
                onBack = onBack,
                onOpenEpisodeDrawer = onOpenEpisodeDrawer,
                onCycleSpeed = onCycleSpeed,
                onCycleResize = onCycleResize,
                onEnterPip = onEnterPip,
                onLockScreen = onLockScreen,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            PlayerCenterControls(
                hasPreviousEpisode = hasPreviousEpisode,
                hasNextEpisode = hasNextEpisode,
                isPlaying = isPlaying,
                onPreviousEpisode = onPreviousEpisode,
                onRewind10 = onRewind10,
                onTogglePlay = onTogglePlay,
                onForward10 = onForward10,
                onNextEpisode = onNextEpisode,
                modifier = Modifier.align(Alignment.Center),
            )

            PlayerBottomTimeline(
                positionMs = positionMs,
                durationMs = durationMs,
                isSeeking = isSeeking,
                sliderPosition = sliderPosition,
                showFallbackSkip85 = !hasSmartSkip,
                onFallbackSkip85 = onFallbackSkip85,
                onSliderChange = onSliderChange,
                onSliderFinished = onSliderFinished,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    animeTitle: String,
    episodeNumber: String,
    qualityBadge: String,
    hasSmartSkip: Boolean,
    hasMultipleEpisodes: Boolean,
    speedLabel: String,
    resizeLabel: String,
    onBack: () -> Unit,
    onOpenEpisodeDrawer: () -> Unit,
    onCycleSpeed: () -> Unit,
    onCycleResize: () -> Unit,
    onEnterPip: () -> Unit,
    onLockScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = Color.White)
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = animeTitle,
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
                    text = "Episodio $episodeNumber",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                )
                BadgePill(
                    text = qualityBadge,
                    backgroundColor = if (qualityBadge == "OFFLINE") BadgeGreen else cs.primary,
                    contentColor = if (qualityBadge == "OFFLINE") Color.White else cs.onPrimary,
                )
                if (hasSmartSkip) {
                    BadgePill(
                        text = "SMART SKIP",
                        backgroundColor = Color.White.copy(alpha = 0.18f),
                        contentColor = Color.White,
                    )
                }
            }
        }

        if (hasMultipleEpisodes) {
            PlayerTopPill(
                icon = Icons.AutoMirrored.Rounded.List,
                label = "Episodi",
                onClick = onOpenEpisodeDrawer,
            )
            Spacer(Modifier.width(8.dp))
        }

        PlayerTopPill(
            icon = Icons.Rounded.Speed,
            label = speedLabel,
            onClick = onCycleSpeed,
        )
        Spacer(Modifier.width(8.dp))

        PlayerTopPill(
            icon = Icons.Rounded.AspectRatio,
            label = resizeLabel,
            onClick = onCycleResize,
        )
        Spacer(Modifier.width(6.dp))

        IconButton(onClick = onEnterPip) {
            Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = Color.White)
        }
        IconButton(onClick = onLockScreen) {
            Icon(Icons.Rounded.Lock, contentDescription = "Blocca schermo", tint = Color.White)
        }
    }
}

@Composable
private fun PlayerCenterControls(
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    isPlaying: Boolean,
    onPreviousEpisode: () -> Unit,
    onRewind10: () -> Unit,
    onTogglePlay: () -> Unit,
    onForward10: () -> Unit,
    onNextEpisode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousEpisode, enabled = hasPreviousEpisode) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = "Precedente",
                tint = if (hasPreviousEpisode) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(34.dp),
            )
        }
        IconButton(onClick = onRewind10) {
            Icon(Icons.Rounded.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(38.dp))
        }
        IconButton(
            onClick = onTogglePlay,
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
        IconButton(onClick = onForward10) {
            Icon(Icons.Rounded.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(38.dp))
        }
        IconButton(onClick = onNextEpisode, enabled = hasNextEpisode) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "Successivo",
                tint = if (hasNextEpisode) Color.White else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun PlayerBottomTimeline(
    positionMs: Long,
    durationMs: Long,
    isSeeking: Boolean,
    sliderPosition: Float,
    showFallbackSkip85: Boolean,
    onFallbackSkip85: () -> Unit,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shownPos = if (isSeeking) (sliderPosition * durationMs).toLong() else positionMs
            Text(
                text = "${formatTimeMs(shownPos)} / ${formatTimeMs(durationMs)}",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )

            if (showFallbackSkip85) {
                PlayerTopPill(
                    icon = Icons.Rounded.FastForward,
                    label = "+85s",
                    onClick = onFallbackSkip85,
                )
            }
        }

        Slider(
            value = sliderPosition,
            onValueChange = onSliderChange,
            onValueChangeFinished = onSliderFinished,
            colors = SliderDefaults.colors(
                thumbColor = cs.primary,
                activeTrackColor = cs.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun BoxScope.PlayerEpisodeDrawer(
    visible: Boolean,
    episodes: List<EpisodeItem>,
    currentIndex: Int,
    historyEntry: WatchHistoryEntry?,
    onClose: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
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
                        text = "Episodi (${episodes.size})",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Chiudi", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(episodes) { idx, ep ->
                        val isCurrent = idx == currentIndex
                        val completed = historyEntry?.episodesProgress?.get(ep.number)?.completed == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isCurrent) cs.primaryContainer else Color.White.copy(alpha = 0.06f))
                                .clickable { onSelectEpisode(idx) }
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

@Composable
private fun PlayerTopPill(
    icon: ImageVector,
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
