package com.carloj005.awanime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.ui.AppViewModel
import com.carloj005.awanime.ui.screens.DetailScreen
import com.carloj005.awanime.ui.screens.DownloadsScreen
import com.carloj005.awanime.ui.screens.HistoryScreen
import com.carloj005.awanime.ui.screens.HomeScreen
import com.carloj005.awanime.ui.screens.PlayerScreen
import com.carloj005.awanime.ui.screens.SearchScreen
import com.carloj005.awanime.ui.theme.AWAnimeTheme
import com.carloj005.awanime.ui.theme.AccentViolet
import com.carloj005.awanime.ui.theme.MidnightBg
import com.carloj005.awanime.ui.theme.SurfaceDark
import com.carloj005.awanime.ui.theme.SurfaceElevated
import com.carloj005.awanime.ui.theme.TextMuted

private enum class BottomTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Cerca", Icons.Rounded.Search),
    HISTORY("Cronologia", Icons.Rounded.History),
    DOWNLOADS("Download", Icons.Rounded.Download),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            AWAnimeTheme {
                AwAnimeRoot()
            }
        }
    }
}

@Composable
private fun AwAnimeRoot(vm: AppViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableStateOf(BottomTab.HOME) }
    val homeState by vm.homeState.collectAsState()
    val searchState by vm.searchState.collectAsState()
    val detailState by vm.detailState.collectAsState()
    val history by vm.history.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val activePlayer by vm.activePlayer.collectAsState()

    val activeDownloadCount = downloads.count {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
    }

    // 1. Player Fullscreen (massima priorità di stack)
    if (activePlayer != null) {
        BackHandler { vm.closePlayer() }
        PlayerScreen(
            request = activePlayer!!,
            onClose = { vm.closePlayer() },
        )
        return
    }

    // 2. Scheda Anime Dettaglio
    if (detailState.anime != null) {
        BackHandler { vm.closeDetail() }
        val histEntry = history.firstOrNull { it.uniqueKey == detailState.anime?.uniqueKey }
        DetailScreen(
            state = detailState,
            historyEntry = histEntry,
            downloads = downloads,
            onBack = { vm.closeDetail() },
            onRetry = { vm.openAnimeDetail(it) },
            onSwitchLanguage = { vm.switchDetailLanguage(it) },
            onPlayEpisode = { anime, eps, idx -> vm.startPlayback(anime, eps, idx) },
            onDownloadEpisode = { anime, ep -> vm.downloadRepo.enqueueDownload(anime, ep) },
        )
        return
    }

    // 3. Navigazione principale 4 tab
    Scaffold(
        containerColor = MidnightBg,
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                BottomTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            if (tab == BottomTab.DOWNLOADS && activeDownloadCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = AccentViolet, contentColor = Color.White) {
                                            Text(activeDownloadCount.toString())
                                        }
                                    }
                                ) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentViolet,
                            selectedTextColor = AccentViolet,
                            indicatorColor = SurfaceElevated,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                BottomTab.HOME -> HomeScreen(
                    state = homeState,
                    history = history,
                    onSelectFilter = { vm.loadHome(it) },
                    onRefresh = { vm.loadHome(homeState.selectedFilter, forceRefresh = true) },
                    onOpenSearch = { selectedTab = BottomTab.SEARCH },
                    onAnimeClick = { vm.openAnimeDetail(it) },
                    onResumeHistory = { vm.resumeFromHistory(it) },
                )

                BottomTab.SEARCH -> SearchScreen(
                    state = searchState,
                    onQueryChange = { vm.updateSearchQuery(it) },
                    onSearchSubmit = { vm.performSearch(it) },
                    onAnimeClick = { vm.openAnimeDetail(it) },
                )

                BottomTab.HISTORY -> HistoryScreen(
                    history = history,
                    onResume = { vm.resumeFromHistory(it) },
                    onOpenDetail = { vm.openAnimeDetail(it) },
                    onRemove = { vm.historyRepo.remove(it) },
                )

                BottomTab.DOWNLOADS -> DownloadsScreen(
                    downloads = downloads,
                    onPlayOffline = { vm.playDownloadedEpisode(it) },
                    onCancel = { vm.downloadRepo.cancelDownload(it) },
                    onDelete = { vm.downloadRepo.deleteDownload(it) },
                )
            }
        }
    }
}
