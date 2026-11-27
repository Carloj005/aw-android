package com.carloj005.awanime

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon as AndroidIcon
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
import androidx.annotation.RequiresApi
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
import androidx.core.content.ContextCompat
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
import com.carloj005.awanime.ui.screens.PipCommand
import com.carloj005.awanime.ui.screens.PlayerScreen
import com.carloj005.awanime.ui.screens.SearchScreen
import com.carloj005.awanime.ui.screens.SettingsScreen
import com.carloj005.awanime.ui.theme.AWAnimeTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private enum class BottomTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Cerca", Icons.Rounded.Search),
    LIBRARY("Libreria", Icons.Rounded.CollectionsBookmark),
    DOWNLOADS("Download", Icons.Rounded.Download),
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_PIP_TOGGLE_PLAY = "com.carloj005.awanime.action.PIP_TOGGLE_PLAY"
        private const val ACTION_PIP_REWIND = "com.carloj005.awanime.action.PIP_REWIND"
        private const val ACTION_PIP_FORWARD = "com.carloj005.awanime.action.PIP_FORWARD"
    }

    private val isInPipState = mutableStateOf(false)
    private val _pipCommands = MutableSharedFlow<PipCommand>(extraBufferCapacity = 8)
    val pipCommands: SharedFlow<PipCommand> = _pipCommands.asSharedFlow()

    @Volatile
    private var shouldAutoEnterPip: Boolean = false
    @Volatile
    private var playerCurrentlyPlaying: Boolean = true

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PIP_TOGGLE_PLAY -> _pipCommands.tryEmit(PipCommand.TOGGLE_PLAY)
                ACTION_PIP_REWIND -> _pipCommands.tryEmit(PipCommand.REWIND_10)
                ACTION_PIP_FORWARD -> _pipCommands.tryEmit(PipCommand.FORWARD_10)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(isPlaying: Boolean, autoEnter: Boolean): PictureInPictureParams {
        val rewindAction = RemoteAction(
            AndroidIcon.createWithResource(this, R.drawable.ic_pip_replay10),
            "Indietro 10s",
            "Indietro di 10 secondi",
            buildPipPendingIntent(ACTION_PIP_REWIND, 101),
        )
        val playPauseAction = RemoteAction(
            AndroidIcon.createWithResource(
                this,
                if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
            ),
            if (isPlaying) "Pausa" else "Riproduci",
            if (isPlaying) "Metti in pausa" else "Riprendi riproduzione",
            buildPipPendingIntent(ACTION_PIP_TOGGLE_PLAY, 102),
        )
        val forwardAction = RemoteAction(
            AndroidIcon.createWithResource(this, R.drawable.ic_pip_forward10),
            "Avanti 10s",
            "Avanti di 10 secondi",
            buildPipPendingIntent(ACTION_PIP_FORWARD, 103),
        )

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(rewindAction, playPauseAction, forwardAction))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    fun enterPipIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                enterPictureInPictureMode(
                    buildPipParams(isPlaying = playerCurrentlyPlaying, autoEnter = shouldAutoEnterPip)
                )
            }
        }
    }

    fun updatePipState(canAutoEnter: Boolean, isPlaying: Boolean) {
        shouldAutoEnterPip = canAutoEnter
        playerCurrentlyPlaying = isPlaying
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                setPictureInPictureParams(
                    buildPipParams(isPlaying = isPlaying, autoEnter = canAutoEnter)
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && shouldAutoEnterPip) {
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

        val filter = IntentFilter().apply {
            addAction(ACTION_PIP_TOGGLE_PLAY)
            addAction(ACTION_PIP_REWIND)
            addAction(ACTION_PIP_FORWARD)
        }
        ContextCompat.registerReceiver(this, pipActionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.Factory)
            val settings by vm.settings.collectAsState()
            val activePlayer by vm.activePlayer.collectAsState()
            val isInPip by isInPipState
            var isPlayerPlaying by rememberSaveable { mutableStateOf(true) }

            LaunchedEffect(activePlayer, settings.autoPipEnabled, isPlayerPlaying) {
                val canAuto = activePlayer != null && settings.autoPipEnabled && isPlayerPlaying
                updatePipState(canAutoEnter = canAuto, isPlaying = isPlayerPlaying)
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
                    pipCommands = pipCommands,
                    onPlayingStateChanged = { playing ->
                        isPlayerPlaying = playing
                        val canAuto = activePlayer != null && settings.autoPipEnabled && playing
                        updatePipState(canAutoEnter = canAuto, isPlaying = playing)
                    },
                    onEnterPip = { enterPipIfSupported() },
                )
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipActionReceiver) }
        super.onDestroy()
    }
}

@Composable
private fun AwAnimeRoot(
    vm: AppViewModel,
    isInPipMode: Boolean,
    pipCommands: SharedFlow<PipCommand>,
    onPlayingStateChanged: (Boolean) -> Unit,
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
    val cacheSizeBytes by vm.cacheSizeBytes.collectAsState()

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            vm.exportBackupToUri(context.contentResolver, uri)
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            vm.importBackupFromUri(context.contentResolver, uri)
        }
    }

    val activeDownloadCount = downloads.count {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
    }

    if (updateState.showDialog && updateState.availableUpdate != null && activePlayer == null) {
        UpdateDialog(
            state = updateState,
            onDismiss = { vm.dismissUpdateDialog() },
            onUpdateNow = { vm.downloadAndInstallUpdate() },
        )
    }

    activePlayer?.let { request ->
        BackHandler { vm.closePlayer() }
        PlayerScreen(
            request = request,
            settings = settings,
            isInPipMode = isInPipMode,
            pipCommands = pipCommands,
            onPlayingStateChanged = onPlayingStateChanged,
            onEnterPip = onEnterPip,
            onClose = { vm.closePlayer() },
        )
        return
    }

    if (showSettings) {
        LaunchedEffect(Unit) { vm.refreshCacheSize() }
        BackHandler { showSettings = false }
        SettingsScreen(
            settings = settings,
            updateState = updateState,
            cacheBytes = cacheSizeBytes,
            feedbackMessage = settingsFeedback,
            onEvent = { event ->
                vm.onSettingsEvent(
                    event = event,
                    onNavigateBack = { showSettings = false },
                    onLaunchExportBackup = { exportBackupLauncher.launch("aw-anime-backup.json") },
                    onLaunchImportBackup = {
                        importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                )
            },
        )
        return
    }

    detailState.anime?.let { activeDetailAnime ->
        BackHandler { vm.closeDetail() }
        val histEntry = history.firstOrNull { it.uniqueKey == activeDetailAnime.uniqueKey }
        val isFav = favorites.any { it.uniqueKey == activeDetailAnime.uniqueKey }
        DetailScreen(
            state = detailState,
            historyEntry = histEntry,
            isFavorite = isFav,
            downloads = downloads,
            shareBaseUrl = vm.shareBaseUrl,
            onBack = { vm.closeDetail() },
            onRetry = { vm.openAnimeDetail(it) },
            onToggleFavorite = { vm.toggleFavorite(it) },
            onSwitchLanguage = { vm.switchDetailLanguage(it) },
            onPlayEpisode = { anime, eps, idx -> vm.startPlayback(anime, eps, idx) },
            onDownloadEpisode = { anime, ep -> vm.enqueueDownload(anime, ep) },
        )
        return
    }

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
                                BadgedBox(badge = { Badge { Text(activeDownloadCount.toString()) } }) {
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
                    onRemoveHistory = { vm.removeHistory(it) },
                    onRemoveFavorite = { vm.removeFavorite(it) },
                )

                BottomTab.DOWNLOADS -> DownloadsScreen(
                    downloads = downloads,
                    onPlayOffline = { vm.playDownloadedEpisode(it) },
                    onRetry = { vm.retryDownload(it) },
                    onCancel = { vm.cancelDownload(it) },
                    onDelete = { vm.deleteDownload(it) },
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
