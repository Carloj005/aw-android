package com.carloj005.awanime.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.CalendarEntry
import com.carloj005.awanime.ui.CalendarFilter
import com.carloj005.awanime.ui.CalendarUiState
import com.carloj005.awanime.ui.components.BadgePill
import com.carloj005.awanime.ui.components.ErrorRetryBox
import com.carloj005.awanime.ui.components.LoadingPlaceholderBox
import com.carloj005.awanime.ui.theme.BadgeGreen
import com.carloj005.awanime.ui.theme.BadgeItaCoral
import com.carloj005.awanime.ui.theme.BadgeSubBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: CalendarUiState,
    favoriteKeys: Set<String>,
    onEnsureLoaded: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDay: (Int) -> Unit,
    onSelectFilter: (CalendarFilter) -> Unit,
    onAnimeClick: (AnimeItem) -> Unit,
    onQuickWatch: (AnimeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        onEnsureLoaded()
    }

    val safeDayIdx = state.selectedDayIndex.coerceIn(0, (state.days.size - 1).coerceAtLeast(0))
    val activeDay = state.days.getOrNull(safeDayIdx)
    val filteredEntries = remember(activeDay, state.filter, favoriteKeys) {
        val raw = activeDay?.entries.orEmpty()
        when (state.filter) {
            CalendarFilter.ALL -> raw
            CalendarFilter.SUB -> raw.filter { !it.isDub }
            CalendarFilter.DUB -> raw.filter { it.isDub }
            CalendarFilter.FAVORITES -> raw.filter { it.anime.uniqueKey in favoriteKeys }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cs.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CalendarMonth,
                    contentDescription = null,
                    tint = cs.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Calendario Uscite",
                    color = cs.onBackground,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = activeDay?.label ?: "Palinsesto settimanale simulcast",
                    color = cs.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FilledTonalIconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna calendario")
            }
        }

        if (state.days.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                itemsIndexed(state.days) { idx, day ->
                    val selected = idx == safeDayIdx
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectDay(idx) },
                        label = {
                            Text(
                                text = "${day.shortLabel} (${day.entries.size})",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp,
                            )
                        },
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

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(CalendarFilter.entries) { filter ->
                    val selected = state.filter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFilter(filter) },
                        label = {
                            Text(
                                text = filter.label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = cs.secondaryContainer,
                            selectedLabelColor = cs.onSecondaryContainer,
                            containerColor = cs.surfaceContainerLow,
                            labelColor = cs.onSurfaceVariant,
                        ),
                        border = null,
                    )
                }
            }
        }

        when {
            state.isLoading && state.days.isEmpty() -> {
                LoadingPlaceholderBox(text = "Caricamento calendario settimanale...")
            }

            state.error != null && state.days.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorRetryBox(message = state.error, onRetry = onRefresh)
                }
            }

            filteredEntries.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CalendarMonth,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (state.filter == CalendarFilter.FAVORITES) {
                                "Nessun anime dei tuoi Preferiti esce in questo giorno"
                            } else {
                                "Nessuna uscita prevista con questo filtro"
                            },
                            color = cs.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            else -> {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = filteredEntries,
                            key = { "${activeDay?.label}_${it.anime.uniqueKey}_${it.nextEpisodeNumber ?: it.airedEpisodeNumber ?: ""}" },
                        ) { entry ->
                            CalendarEntryCard(
                                entry = entry,
                                isFavorite = entry.anime.uniqueKey in favoriteKeys,
                                onOpenDetail = { onAnimeClick(entry.anime) },
                                onQuickWatch = { onQuickWatch(entry.anime) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEntryCard(
    entry: CalendarEntry,
    isFavorite: Boolean,
    onOpenDetail: () -> Unit,
    onQuickWatch: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val anime = entry.anime
    val epDisplay = when {
        entry.isAired && !entry.airedEpisodeNumber.isNullOrBlank() -> "Ep. ${entry.airedEpisodeNumber} uscito"
        !entry.nextEpisodeNumber.isNullOrBlank() -> "Ep. ${entry.nextEpisodeNumber}"
        !entry.airedEpisodeNumber.isNullOrBlank() -> "Ep. ${entry.airedEpisodeNumber}"
        else -> null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = anime.displayImageUrl,
                contentDescription = anime.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(68.dp)
                    .height(98.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surfaceContainerHigh),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = anime.title,
                        color = cs.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isFavorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = "Nei preferiti",
                            tint = BadgeItaCoral,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgePill(
                        text = if (entry.isDub) "ITA" else "SUB",
                        backgroundColor = if (entry.isDub) BadgeItaCoral else BadgeSubBlue,
                    )
                    if (epDisplay != null) {
                        BadgePill(
                            text = epDisplay,
                            backgroundColor = if (entry.isAired) BadgeGreen.copy(alpha = 0.20f) else cs.primaryContainer,
                            contentColor = if (entry.isAired) BadgeGreen else cs.onPrimaryContainer,
                        )
                    }
                    if (entry.isLate) {
                        BadgePill(
                            text = "In ritardo",
                            backgroundColor = cs.errorContainer,
                            contentColor = cs.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (entry.isAired) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = BadgeGreen,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = entry.releaseTimeLabel?.let { "Disponibile dalle $it" } ?: "Episodio disponibile",
                            color = BadgeGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else if (!entry.pausedUntil.isNullOrBlank()) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "In pausa",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    } else if (!entry.releaseTimeLabel.isNullOrBlank()) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = "Previsto alle ${entry.releaseTimeLabel}",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    } else {
                        Text(
                            text = anime.type,
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            if (entry.isAired) {
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(onClick = onQuickWatch) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Guarda episodio",
                    )
                }
            }
        }
    }
}
