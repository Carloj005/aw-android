package com.carloj005.awanime.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.ui.HomeUiState
import com.carloj005.awanime.ui.components.AnimePosterCard
import com.carloj005.awanime.ui.components.ContinueWatchingCard
import com.carloj005.awanime.ui.components.ErrorRetryBox
import com.carloj005.awanime.ui.components.HeroCarouselSection
import com.carloj005.awanime.ui.components.LoadingPlaceholderBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    history: List<WatchHistoryEntry>,
    favorites: List<AnimeItem>,
    hasUpdateAvailable: Boolean,
    onSelectFilter: (LatestFilter) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onAnimeClick: (AnimeItem) -> Unit,
    onQuickWatch: (AnimeItem) -> Unit,
    onResumeHistory: (WatchHistoryEntry) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val continueWatching = remember(history) {
        history.filter { it.shouldShowInContinueWatching }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cs.background),
    ) {
        // Material 3 Expressive Header
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
                    imageVector = Icons.Rounded.PlayCircle,
                    contentDescription = null,
                    tint = cs.onPrimaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AW Anime",
                    color = cs.onBackground,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = state.activeDomain,
                    color = cs.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FilledTonalIconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna")
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalIconButton(onClick = onOpenSettings) {
                if (hasUpdateAvailable) {
                    BadgedBox(badge = { Badge(containerColor = cs.error) }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Impostazioni")
                    }
                } else {
                    Icon(Icons.Rounded.Settings, contentDescription = "Impostazioni")
                }
            }
        }

        // Expressive Pill Filter Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            items(LatestFilter.entries) { filter ->
                val selected = state.selectedFilter == filter
                FilterChip(
                    selected = selected,
                    onClick = { onSelectFilter(filter) },
                    label = {
                        Text(
                            text = filter.label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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

        when {
            state.isLoading && state.items.isEmpty() -> {
                LoadingPlaceholderBox(text = "Connessione a ${state.activeDomain}...")
            }

            state.error != null && state.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorRetryBox(message = state.error, onRetry = onRefresh)
                }
            }

            else -> {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Hero Carousel in evidenza nella tab "Tutti"
                        if (state.selectedFilter == LatestFilter.ALL && state.heroCarousel.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                HeroCarouselSection(
                                    animes = state.heroCarousel,
                                    onWatchNow = onQuickWatch,
                                    onOpenDetail = onAnimeClick,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }

                        // Carosello "Continua a guardare" (nasconde stagioni/anime già completati fino all'ultimo episodio)
                        if (continueWatching.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                    Text(
                                        text = "Continua a guardare",
                                        color = cs.onBackground,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(bottom = 10.dp),
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(continueWatching.take(10), key = { it.uniqueKey }) { entry ->
                                            ContinueWatchingCard(
                                                entry = entry,
                                                onResume = { onResumeHistory(entry) },
                                                onOpenDetail = { onAnimeClick(entry.anime) },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Carosello "I tuoi Preferiti"
                        if (favorites.isNotEmpty()) {
                            item(span = { GridItemSpan(2) }) {
                                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                    Text(
                                        text = "I tuoi Preferiti",
                                        color = cs.onBackground,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(bottom = 10.dp),
                                    )
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        items(favorites.take(12), key = { "fav_${it.uniqueKey}" }) { fav ->
                                            AnimePosterCard(
                                                anime = fav,
                                                onClick = { onAnimeClick(fav) },
                                                modifier = Modifier.width(138.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        item(span = { GridItemSpan(2) }) {
                            val headerText = when (state.selectedFilter) {
                                LatestFilter.TRENDING -> "Top Anime in Tendenza"
                                LatestFilter.DUB -> "Ultimi Episodi Doppiati (ITA)"
                                LatestFilter.SUB -> "Ultimi Episodi SUB ITA"
                                LatestFilter.ALL -> "Ultimi Episodi Usciti"
                            }
                            Text(
                                text = headerText,
                                color = cs.onBackground,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            )
                        }

                        items(
                            items = state.items,
                            key = { "${it.uniqueKey}_${it.latestVideoId ?: it.latestEpisodeNum ?: ""}" },
                        ) { anime ->
                            AnimePosterCard(
                                anime = anime,
                                onClick = { onAnimeClick(anime) },
                            )
                        }
                    }
                }
            }
        }
    }
}
