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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.CalendarEntry
import com.carloj005.awanime.data.model.DiscoveredSequel
import com.carloj005.awanime.data.model.TrackedNotificationPrefs
import com.carloj005.awanime.data.notifications.SequelTracker
import com.carloj005.awanime.data.notifications.UserFranchiseProfile
import com.carloj005.awanime.data.storage.TrackedNotificationsRepository
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
    watchingKeys: Set<String>,
    userFranchiseProfiles: Map<String, UserFranchiseProfile>,
    allKnownAnimeIds: Set<String>,
    notifPrefs: TrackedNotificationPrefs,
    discoveredSequels: List<DiscoveredSequel>,
    onEnsureLoaded: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDay: (Int) -> Unit,
    onSelectFilter: (CalendarFilter) -> Unit,
    onToggleNotification: (AnimeItem) -> Unit,
    onDismissSequel: (String) -> Unit,
    onAnimeClick: (AnimeItem) -> Unit,
    onQuickWatch: (AnimeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        onEnsureLoaded()
    }

    fun isEntryNotifEnabled(entry: CalendarEntry): Boolean =
        TrackedNotificationsRepository.isEnabledForAnime(
            prefs = notifPrefs,
            uniqueKey = entry.anime.uniqueKey,
            favoriteKeys = favoriteKeys,
            watchingKeys = watchingKeys,
        )

    fun isEntryFranchiseSequel(entry: CalendarEntry): Boolean {
        val key = entry.anime.uniqueKey
        if (key in notifPrefs.explicitlyMutedKeys) return false
        if (key in notifPrefs.discoveredSequelKeys) return true
        return SequelTracker.isCalendarFranchiseSequel(
            candidate = entry.anime,
            profiles = userFranchiseProfiles,
            allKnownAnimeIds = allKnownAnimeIds,
        )
    }

    fun matchesActiveFilter(entry: CalendarEntry): Boolean = when (state.filter) {
        CalendarFilter.MY_ANIME -> isEntryNotifEnabled(entry) || isEntryFranchiseSequel(entry)
        CalendarFilter.ALL -> true
        CalendarFilter.SUB -> !entry.isDub
        CalendarFilter.DUB -> entry.isDub
    }

    val safeDayIdx = state.selectedDayIndex.coerceIn(0, (state.days.size - 1).coerceAtLeast(0))
    val activeDay = state.days.getOrNull(safeDayIdx)
    val dayCounts = remember(state.days, state.filter, favoriteKeys, watchingKeys, userFranchiseProfiles, allKnownAnimeIds, notifPrefs) {
        state.days.map { day -> day.entries.count { matchesActiveFilter(it) } }
    }
    val filteredEntries = remember(activeDay, state.filter, favoriteKeys, watchingKeys, userFranchiseProfiles, allKnownAnimeIds, notifPrefs) {
        activeDay?.entries.orEmpty().filter { matchesActiveFilter(it) }
    }
    val totalMyAnimeThisWeek = remember(dayCounts) { dayCounts.sum() }

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

        if (discoveredSequels.isNotEmpty()) {
            val topSequel = discoveredSequels.first()
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { onAnimeClick(topSequel.newSeason) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = cs.onTertiaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Nuova stagione salvata automaticamente!",
                            color = cs.onTertiaryContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = "${topSequel.newSeason.title} (sequel di ${topSequel.parentAnime.title})",
                            color = cs.onTertiaryContainer.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { onDismissSequel(topSequel.newSeason.uniqueKey) },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Chiudi avviso",
                            tint = cs.onTertiaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
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
                    val count = dayCounts.getOrElse(idx) { day.entries.size }
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectDay(idx) },
                        label = {
                            Text(
                                text = "${day.shortLabel} ($count)",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp,
                            )
                        },
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = cs.primaryContainer,
                            selectedLabelColor = cs.onPrimaryContainer,
                            containerColor = cs.surfaceContainer,
                            labelColor = if (count == 0 && state.filter == CalendarFilter.MY_ANIME) {
                                cs.onSurfaceVariant.copy(alpha = 0.55f)
                            } else {
                                cs.onSurfaceVariant
                            },
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
                        val titleMsg = when {
                            state.filter == CalendarFilter.MY_ANIME && totalMyAnimeThisWeek > 0 ->
                                "Nessun tuo anime esce in questo giorno"
                            state.filter == CalendarFilter.MY_ANIME ->
                                "Nessun anime seguito in onda questa settimana"
                            else ->
                                "Nessuna uscita prevista con questo filtro"
                        }
                        val subMsg = when {
                            state.filter == CalendarFilter.MY_ANIME && totalMyAnimeThisWeek > 0 ->
                                "Hai $totalMyAnimeThisWeek uscite negli altri giorni della settimana, oppure sfoglia tutto il palinsesto."
                            state.filter == CalendarFilter.MY_ANIME ->
                                "Tocca la campanella 🔔 sugli anime che vuoi seguire nel palinsesto completo: appariranno qui e riceverai le notifiche solo per loro."
                            else -> null
                        }
                        Text(
                            text = titleMsg,
                            color = cs.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        if (subMsg != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = subMsg,
                                color = cs.onSurfaceVariant,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (state.filter == CalendarFilter.MY_ANIME) {
                            Spacer(Modifier.height(14.dp))
                            FilledTonalButton(
                                onClick = { onSelectFilter(CalendarFilter.ALL) },
                                shape = CircleShape,
                            ) {
                                Text("Mostra tutto il palinsesto", fontWeight = FontWeight.SemiBold)
                            }
                        }
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
                                isNotificationEnabled = isEntryNotifEnabled(entry),
                                isNewSeasonMatch = isEntryFranchiseSequel(entry),
                                onToggleNotification = { onToggleNotification(entry.anime) },
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
    isNotificationEnabled: Boolean,
    isNewSeasonMatch: Boolean,
    onToggleNotification: () -> Unit,
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
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = "Nei preferiti",
                            tint = BadgeItaCoral,
                            modifier = Modifier.size(15.dp),
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
                    if (isNewSeasonMatch) {
                        BadgePill(
                            text = "Nuova Stagione",
                            backgroundColor = cs.tertiaryContainer,
                            contentColor = cs.onTertiaryContainer,
                        )
                    } else if (entry.isLate) {
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = onToggleNotification,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = if (isNotificationEnabled) {
                            Icons.Rounded.NotificationsActive
                        } else {
                            Icons.Rounded.NotificationsOff
                        },
                        contentDescription = if (isNotificationEnabled) {
                            "Disattiva notifiche per questo anime"
                        } else {
                            "Segui e attiva notifiche per questo anime"
                        },
                        tint = if (isNotificationEnabled) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (entry.isAired) {
                    FilledTonalIconButton(
                        onClick = onQuickWatch,
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Guarda episodio",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
