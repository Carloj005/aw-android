package com.carloj005.awanime

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.ThemeMode
import com.carloj005.awanime.data.model.formatBytes
import com.carloj005.awanime.ui.AppViewModel
import com.carloj005.awanime.ui.UpdateUiState
import com.carloj005.awanime.ui.screens.DetailScreen
import com.carloj005.awanime.ui.screens.DownloadsScreen
import com.carloj005.awanime.ui.screens.HistoryScreen
import com.carloj005.awanime.ui.screens.HomeScreen
import com.carloj005.awanime.ui.screens.PlayerScreen
import com.carloj005.awanime.ui.screens.SearchScreen
import com.carloj005.awanime.ui.screens.SettingsScreen
import com.carloj005.awanime.ui.theme.AWAnimeTheme

private enum class BottomTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Cerca", Icons.Rounded.Search),
    LIBRARY("Libreria", Icons.Rounded.CollectionsBookmark),
    DOWNLOADS("Download", Icons.Rounded.Download),
}

class MainActivity : ComponentActivity() {

    private val isInPipState = mutableStateOf(false)
    @Volatile
    private var shouldAutoEnterPip: Boolean = false

    fun enterPipIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldAutoEnterPip) {
            enterPipIfSupported()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipState.value = isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            val activePlayer by vm.activePlayer.collectAsState()
            val isInPip by isInPipState

            LaunchedEffect(activePlayer, settings.autoPipEnabled) {
                shouldAutoEnterPip = activePlayer != null && settings.autoPipEnabled
            }

            val isDark = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                )
            }

            AWAnimeTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColors,
            ) {
                AwAnimeRoot(
                    vm = vm,
                    isInPipMode = isInPip,
                    onEnterPip = { enterPipIfSupported() },
                )
            }
        }
    }
}

@Composable
private fun AwAnimeRoot(
    vm: AppViewModel,
    isInPipMode: Boolean,
    onEnterPip: () -> Unit,
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(BottomTab.HOME) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val homeState by vm.homeState.collectAsState()
    val searchState by vm.searchState.collectAsState()
    val detailState by vm.detailState.collectAsState()
    val history by vm.history.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val settings by vm.settings.collectAsState()
    val activePlayer by vm.activePlayer.collectAsState()
    val updateState by vm.updateState.collectAsState()
    val settingsFeedback by vm.settingsFeedback.collectAsState()

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val payload = vm.buildBackupJson()
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                vm.setSettingsFeedback("Backup esportato con successo!")
            }.onFailure { err ->
                vm.setSettingsFeedback("Errore esportazione backup: ${err.message}")
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    inp.bufferedReader(Charsets.UTF_8).readText()
                }.orEmpty()
            }.mapCatching { text ->
                vm.importBackupJson(text).getOrThrow()
            }.onSuccess { msg ->
                vm.setSettingsFeedback(msg)
            }.onFailure { err ->
                vm.setSettingsFeedback("File backup non valido: ${err.message}")
            }
        }
    }

    val activeDownloadCount = downloads.count {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
    }

    // Dialogo Aggiornamento Automatico (GitHub Releases con verifica SHA-256 + Firma)
    if (updateState.showDialog && updateState.availableUpdate != null && activePlayer == null) {
        UpdateDialog(
            state = updateState,
            onDismiss = { vm.dismissUpdateDialog() },
            onUpdateNow = { vm.downloadAndInstallUpdate() },
        )
    }

    // 1. Player Fullscreen / PiP
    if (activePlayer != null) {
        BackHandler { vm.closePlayer() }
        PlayerScreen(
            request = activePlayer!!,
            settings = settings,
            isInPipMode = isInPipMode,
            onEnterPip = onEnterPip,
            onClose = { vm.closePlayer() },
        )
        return
    }

    // 2. Schermata Impostazioni
    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            settings = settings,
            updateState = updateState,
            feedbackMessage = settingsFeedback,
            onBack = { showSettings = false },
            onThemeModeChange = { vm.setThemeMode(it) },
            onDynamicColorsChange = { vm.setDynamicColors(it) },
            onAutoSkipIntroChange = { vm.setAutoSkipIntro(it) },
            onAutoSkipOutroChange = { vm.setAutoSkipOutro(it) },
            onAutoPipChange = { vm.setAutoPipEnabled(it) },
            onWifiOnlyDownloadsChange = { vm.setWifiOnlyDownloads(it) },
            onAutoCheckUpdatesChange = { vm.setAutoCheckUpdates(it) },
            onCheckUpdatesNow = { vm.checkForUpdates(manual = true) },
            onOpenUpdateDialog = { vm.showUpdateDialog() },
            onExportBackup = { exportBackupLauncher.launch("aw-anime-backup.json") },
            onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            onClearCache = { onDone -> vm.clearAppCache(context, onDone) },
        )
        return
    }

    // 3. Scheda Anime Dettaglio
    if (detailState.anime != null) {
        BackHandler { vm.closeDetail() }
        val histEntry = history.firstOrNull { it.uniqueKey == detailState.anime?.uniqueKey }
        val isFav = favorites.any { it.uniqueKey == detailState.anime?.uniqueKey }
        DetailScreen(
            state = detailState,
            historyEntry = histEntry,
            isFavorite = isFav,
            downloads = downloads,
            onBack = { vm.closeDetail() },
            onRetry = { vm.openAnimeDetail(it) },
            onToggleFavorite = { vm.toggleFavorite(it) },
            onSwitchLanguage = { vm.switchDetailLanguage(it) },
            onPlayEpisode = { anime, eps, idx -> vm.startPlayback(anime, eps, idx) },
            onDownloadEpisode = { anime, ep -> vm.downloadRepo.enqueueDownload(anime, ep) },
        )
        return
    }

    // 4. Navigazione principale 4 tab Material You 3 Expressive
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                BottomTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            if (tab == BottomTab.DOWNLOADS && activeDownloadCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge { Text(activeDownloadCount.toString()) }
                                    }
                                ) {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label, fontWeight = FontWeight.SemiBold) },
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
                    favorites = favorites,
                    hasUpdateAvailable = updateState.availableUpdate != null,
                    onSelectFilter = { vm.loadHome(it) },
                    onRefresh = { vm.loadHome(homeState.selectedFilter, forceRefresh = true) },
                    onOpenSettings = { showSettings = true },
                    onAnimeClick = { vm.openAnimeDetail(it) },
                    onQuickWatch = { vm.quickWatchAnime(it) },
                    onResumeHistory = { vm.resumeFromHistory(it) },
                )

                BottomTab.SEARCH -> SearchScreen(
                    state = searchState,
                    onQueryChange = { vm.updateSearchQuery(it) },
                    onFilterChange = { vm.setSearchFilter(it) },
                    onSearchSubmit = { vm.performSearch(it) },
                    onClearRecent = { vm.clearRecentQueries() },
                    onAnimeClick = { vm.openAnimeDetail(it) },
                )

                BottomTab.LIBRARY -> HistoryScreen(
                    history = history,
                    favorites = favorites,
                    onResume = { vm.resumeFromHistory(it) },
                    onOpenDetail = { vm.openAnimeDetail(it) },
                    onRemoveHistory = { vm.historyRepo.remove(it) },
                    onRemoveFavorite = { vm.removeFavorite(it) },
                )

                BottomTab.DOWNLOADS -> DownloadsScreen(
                    downloads = downloads,
                    onPlayOffline = { vm.playDownloadedEpisode(it) },
                    onRetry = { vm.downloadRepo.retryDownload(it) },
                    onCancel = { vm.downloadRepo.cancelDownload(it) },
                    onDelete = { vm.downloadRepo.deleteDownload(it) },
                )
            }
        }
    }
}

@Composable
private fun UpdateDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onUpdateNow: () -> Unit,
) {
    val info = state.availableUpdate ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = {
            Text(
                text = "Aggiornamento ${info.tagName} disponibile",
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Versione attuale: v${BuildConfig.VERSION_NAME} → v${info.versionName}" +
                        if (info.sizeBytes > 0L) " (${formatBytes(info.sizeBytes)})" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = info.changelog,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.isDownloading) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { state.downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)} (${(state.downloadProgress * 100).toInt()}%)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                } else if (state.needsInstallPermission) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Abilita 'Consenti da questa sorgente' nelle impostazioni appena aperte, poi tocca di nuovo 'Installa ora'.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (state.statusMessage != null && state.statusMessage.startsWith("Aggiornamento bloccato")) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.statusMessage,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateNow,
                enabled = !state.isDownloading,
                shape = CircleShape,
            ) {
                Text(
                    when {
                        state.isDownloading -> "Download..."
                        state.pendingApkFile != null -> "Installa ora"
                        else -> "Scarica e verifica"
                    }
                )
            }
        },
        dismissButton = {
            if (!state.isDownloading) {
                TextButton(onClick = onDismiss) { Text("Più tardi") }
            }
        },
    )
}
