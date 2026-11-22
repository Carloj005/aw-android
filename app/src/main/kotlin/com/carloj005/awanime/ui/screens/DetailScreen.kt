package com.carloj005.awanime.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.EpisodeProgress
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.formatTimeMs
import com.carloj005.awanime.ui.DetailUiState
import com.carloj005.awanime.ui.components.BadgePill
import com.carloj005.awanime.ui.components.ErrorRetryBox
import com.carloj005.awanime.ui.theme.BadgeGreen
import com.carloj005.awanime.ui.theme.BadgeItaCoral
import com.carloj005.awanime.ui.theme.BadgeSubBlue
import com.carloj005.awanime.ui.theme.StarAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    state: DetailUiState,
    historyEntry: WatchHistoryEntry?,
    downloads: List<DownloadEntry>,
    onBack: () -> Unit,
    onRetry: (AnimeItem) -> Unit,
    onSwitchLanguage: (Boolean) -> Unit,
    onPlayEpisode: (AnimeItem, List<EpisodeItem>, Int) -> Unit,
    onDownloadEpisode: (AnimeItem, EpisodeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anime = state.anime ?: return
    val cs = MaterialTheme.colorScheme
    var plotExpanded by remember(anime.uniqueKey) { mutableStateOf(false) }
    var selectedRangeIndex by remember(anime.uniqueKey) { mutableIntStateOf(0) }

    val chunkSize = 100
    val episodeChunks = remember(state.episodes) {
        if (state.episodes.isEmpty()) emptyList() else state.episodes.chunked(chunkSize)
    }
    val safeChunkIndex = selectedRangeIndex.coerceIn(0, (episodeChunks.size - 1).coerceAtLeast(0))
    val visibleEpisodes = episodeChunks.getOrNull(safeChunkIndex).orEmpty()
    val chunkOffset = safeChunkIndex * chunkSize

    val downloadMap = remember(downloads, anime.id, anime.isDub) {
        downloads
            .filter { it.animeId == anime.id && it.isDub == anime.isDub }
            .associateBy { it.episodeNum }
    }

    val primaryEpisodeIndex = remember(state.episodes, historyEntry) {
        if (state.episodes.isEmpty()) {
            0
        } else if (historyEntry != null) {
            val idx = state.episodes.indexOfFirst { it.number == historyEntry.currentEpisodeNum }
            if (idx >= 0) {
                if (historyEntry.completed && idx + 1 < state.episodes.size) idx + 1 else idx
            } else {
                0
            }
        } else {
            0
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(cs.background),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 36.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Hero Banner + Poster + Metadati
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp),
                ) {
                    AsyncImage(
                        model = anime.displayBannerUrl,
                        contentDescription = anime.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x66000000),
                                        Color(0x99000000),
                                        cs.background,
                                    ),
                                )
                            ),
                    )

                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(12.dp)
                            .clip(CircleShape)
                            .background(Color(0x99000000)),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Indietro",
                            tint = Color.White,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        AsyncImage(
                            model = anime.displayImageUrl,
                            contentDescription = anime.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(110.dp)
                                .height(160.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(cs.surfaceContainerHigh),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = anime.title,
                                color = cs.onBackground,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 23.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                BadgePill(
                                    text = if (anime.isDub) "ITA" else anime.audioLabel.uppercase(),
                                    backgroundColor = if (anime.isDub) BadgeItaCoral else BadgeSubBlue,
                                )
                                BadgePill(
                                    text = anime.type,
                                    backgroundColor = cs.surfaceContainerHigh,
                                    contentColor = cs.onSurface,
                                )
                                BadgePill(
                                    text = anime.status,
                                    backgroundColor = cs.secondaryContainer,
                                    contentColor = cs.onSecondaryContainer,
                                )
                                anime.score?.let { sc ->
                                    Row(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(cs.surfaceContainerHigh)
                                            .padding(horizontal = 7.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Rounded.Star,
                                            contentDescription = null,
                                            tint = StarAmber,
                                            modifier = Modifier.size(12.dp),
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(sc, color = cs.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            val metaLine = buildList {
                                if (anime.season.isNotBlank() && anime.year != null) add("${anime.season} ${anime.year}")
                                else anime.year?.let { add(it.toString()) }
                                anime.runtimeMinutes?.let { add("$it min") }
                                if (anime.studios.isNotEmpty()) add(anime.studios.joinToString(", "))
                            }.joinToString(" • ")
                            if (metaLine.isNotBlank()) {
                                Text(
                                    text = metaLine,
                                    color = cs.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // Switch lingua SUB / ITA + CTA Guarda/Riprendi
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (anime.hasSubVariant && anime.hasDubVariant) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        ) {
                            SegmentedButton(
                                selected = !anime.isDub,
                                onClick = { onSwitchLanguage(false) },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) {
                                Text("SUB ITA (Originale)", fontWeight = FontWeight.SemiBold)
                            }
                            SegmentedButton(
                                selected = anime.isDub,
                                onClick = { onSwitchLanguage(true) },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) {
                                Text("Doppiato ITA", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    if (state.episodes.isNotEmpty()) {
                        val targetEp = state.episodes[primaryEpisodeIndex]
                        val ctaText = when {
                            historyEntry == null -> "Guarda Episodio ${targetEp.number}"
                            historyEntry.completed -> "Guarda Prossimo (Ep. ${targetEp.number})"
                            else -> "Riprendi Ep. ${targetEp.number} (${formatTimeMs(historyEntry.progressMs)})"
                        }
                        Button(
                            onClick = { onPlayEpisode(anime, state.episodes, primaryEpisodeIndex) },
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(ctaText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Generi & Trama
            if (anime.genres.isNotEmpty() || anime.plot.isNotBlank()) {
                item {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            if (anime.genres.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(bottom = if (anime.plot.isNotBlank()) 10.dp else 0.dp),
                                ) {
                                    anime.genres.forEach { genre ->
                                        BadgePill(
                                            text = genre,
                                            backgroundColor = cs.surfaceContainerHigh,
                                            contentColor = cs.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            if (anime.plot.isNotBlank()) {
                                Text(
                                    text = anime.plot,
                                    color = cs.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    maxLines = if (plotExpanded) Int.MAX_VALUE else 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { plotExpanded = !plotExpanded },
                                )
                                Text(
                                    text = if (plotExpanded) "Mostra meno" else "Leggi tutta la trama",
                                    color = cs.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .clickable { plotExpanded = !plotExpanded },
                                )
                            }
                        }
                    }
                }
            }

            // Header Episodi + Selettore Range
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(
                        text = "Episodi (${state.episodes.size})",
                        color = cs.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    if (episodeChunks.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(episodeChunks) { idx, chunk ->
                                val label = "${chunk.first().number} - ${chunk.last().number}"
                                FilterChip(
                                    selected = idx == safeChunkIndex,
                                    onClick = { selectedRangeIndex = idx },
                                    label = { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                    shape = CircleShape,
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = cs.primaryContainer,
                                        selectedLabelColor = cs.onPrimaryContainer,
                                        containerColor = cs.surfaceContainer,
                                        labelColor = cs.onSurfaceVariant,
                                    ),
                                    border = null,
                                )
                            }
                        }
                    }
                }
            }

            when {
                state.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = cs.primary)
                        }
                    }
                }

                state.error != null && state.episodes.isEmpty() -> {
                    item {
                        ErrorRetryBox(message = state.error, onRetry = { onRetry(anime) })
                    }
                }

                state.episodes.isEmpty() -> {
                    item {
                        Text(
                            text = "Nessun episodio ancora disponibile per questa lingua.",
                            color = cs.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }

                else -> {
                    itemsIndexed(
                        items = visibleEpisodes,
                        key = { _, ep -> "${ep.videoId}_${ep.number}" },
                    ) { localIdx, episode ->
                        val globalIdx = chunkOffset + localIdx
                        val epProgress = historyEntry?.episodesProgress?.get(episode.number)
                        val dlEntry = downloadMap[episode.number]
                        EpisodeRow(
                            episode = episode,
                            progress = epProgress,
                            downloadEntry = dlEntry,
                            onPlay = { onPlayEpisode(anime, state.episodes, globalIdx) },
                            onDownload = { onDownloadEpisode(anime, episode) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeItem,
    progress: EpisodeProgress?,
    downloadEntry: DownloadEntry?,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onPlay),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (progress?.completed == true) BadgeGreen.copy(alpha = 0.18f)
                            else cs.primaryContainer
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (progress?.completed == true) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "Visto",
                            tint = BadgeGreen,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "Riproduci",
                            tint = cs.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Episodio ${episode.number}",
                            color = cs.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (episode.isFiller) {
                            Spacer(Modifier.width(6.dp))
                            BadgePill(
                                text = "FILLER",
                                backgroundColor = cs.surfaceContainerHigh,
                                contentColor = cs.onSurfaceVariant,
                            )
                        }
                    }
                    val subtitle = buildList {
                        if (episode.runtimeSeconds > 0) add("${episode.runtimeSeconds / 60} min")
                        if (progress != null && !progress.completed && progress.positionMs > 0L) {
                            add("Riprendi da ${formatTimeMs(progress.positionMs)}")
                        } else if (progress?.completed == true) {
                            add("Completato")
                        }
                        if (downloadEntry?.status == DownloadStatus.COMPLETED) {
                            add("Offline pronto")
                        }
                    }.joinToString(" • ")

                    if (subtitle.isNotBlank()) {
                        Text(text = subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }
                }

                when (downloadEntry?.status) {
                    DownloadStatus.COMPLETED -> {
                        Icon(
                            Icons.Rounded.DownloadDone,
                            contentDescription = "Scaricato offline",
                            tint = BadgeGreen,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(22.dp),
                        )
                    }

                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        Box(
                            modifier = Modifier.size(38.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                progress = { downloadEntry.progressFraction.coerceAtLeast(0.08f) },
                                color = cs.primary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    else -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = "Scarica episodio",
                                tint = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (progress != null && progress.fraction > 0f && !progress.completed) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerHigh,
                )
            }
        }
    }
}
