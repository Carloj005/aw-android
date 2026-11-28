package com.carloj005.awanime.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carloj005.awanime.BuildConfig
import com.carloj005.awanime.data.integrations.BugReportHelper
import com.carloj005.awanime.data.integrations.ReportKind
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.ThemeMode
import com.carloj005.awanime.data.model.formatBytes
import com.carloj005.awanime.ui.UpdateUiState

sealed interface SettingsEvent {
    data object NavigateBack : SettingsEvent
    data class ChangeThemeMode(val mode: ThemeMode) : SettingsEvent
    data class ToggleDynamicColors(val enabled: Boolean) : SettingsEvent
    data class ToggleAutoSkipIntro(val enabled: Boolean) : SettingsEvent
    data class ToggleAutoSkipOutro(val enabled: Boolean) : SettingsEvent
    data class ToggleAutoPip(val enabled: Boolean) : SettingsEvent
    data class ToggleWifiOnlyDownloads(val enabled: Boolean) : SettingsEvent
    data class ToggleFavoriteNotifications(val enabled: Boolean) : SettingsEvent
    data class ChangeNotifyIntervalHours(val hours: Int) : SettingsEvent
    data object CheckFavoriteEpisodesNow : SettingsEvent
    data class ToggleSuiteTvQuickAction(val enabled: Boolean) : SettingsEvent
    data object ExportSuiteTvHistory : SettingsEvent
    data object ImportSuiteTvHistory : SettingsEvent
    data object OpenSuiteTv : SettingsEvent
    data object OpenSupportedLinksSettings : SettingsEvent
    data class ToggleAutoCheckUpdates(val enabled: Boolean) : SettingsEvent
    data object CheckUpdatesNow : SettingsEvent
    data object OpenUpdateDialog : SettingsEvent
    data object ExportBackup : SettingsEvent
    data object ImportBackup : SettingsEvent
    data object ClearCache : SettingsEvent
    data class SubmitBugReport(
        val kind: ReportKind,
        val title: String,
        val description: String,
        val onDone: (Boolean) -> Unit,
    ) : SettingsEvent
    data class ShowFeedback(val message: String) : SettingsEvent
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    updateState: UpdateUiState,
    cacheBytes: Long,
    activeDomain: String,
    feedbackMessage: String?,
    onConsumeFeedback: () -> Unit,
    isCheckingFavorites: Boolean,
    isImportingSuiteTv: Boolean,
    isSubmittingBugReport: Boolean,
    isSuiteTvInstalled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showBugReportDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(feedbackMessage) {
        val msg = feedbackMessage ?: return@LaunchedEffect
        onConsumeFeedback()
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(
            message = msg,
            duration = SnackbarDuration.Short,
            withDismissAction = true,
        )
    }

    if (showBugReportDialog) {
        BugReportDialog(
            activeDomain = activeDomain,
            isSubmitting = isSubmittingBugReport,
            onDismiss = { if (!isSubmittingBugReport) showBugReportDialog = false },
            onSubmitAnonymous = { kind, title, desc ->
                onEvent(
                    SettingsEvent.SubmitBugReport(
                        kind = kind,
                        title = title,
                        description = desc,
                        onDone = { success ->
                            if (success) showBugReportDialog = false
                        },
                    )
                )
            },
            onShareReport = { kind, title, desc ->
                BugReportHelper.shareReport(context, kind, title, desc, activeDomain)
            },
            onCopyReport = { kind, title, desc ->
                val copied = BugReportHelper.copyReportToClipboard(context, kind, title, desc, activeDomain)
                onEvent(
                    SettingsEvent.ShowFeedback(
                        if (copied) "Report diagnostico copiato negli appunti!"
                        else "Impossibile copiare il report."
                    )
                )
            },
            onSubmitGitHub = { kind, title, desc ->
                val opened = BugReportHelper.openGitHubIssueForm(context, kind, title, desc, activeDomain)
                showBugReportDialog = false
                if (!opened) {
                    onEvent(SettingsEvent.ShowFeedback("Impossibile aprire il browser: usa 'Copia' o 'Condividi'."))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(cs.background)
                .statusBarsPadding()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onEvent(SettingsEvent.NavigateBack) }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro", tint = cs.onBackground)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Impostazioni",
                    color = cs.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    AppearanceSection(settings = settings, onEvent = onEvent)
                }

                item {
                    PlayerPreferencesSection(settings = settings, onEvent = onEvent)
                }

                item {
                    FavoriteNotificationsSection(
                        settings = settings,
                        isCheckingFavorites = isCheckingFavorites,
                        onEvent = onEvent,
                    )
                }

                item {
                    SuiteTvIntegrationSection(
                        settings = settings,
                        isSuiteTvInstalled = isSuiteTvInstalled,
                        isImportingSuiteTv = isImportingSuiteTv,
                        onEvent = onEvent,
                    )
                }

                item {
                    NetworkAndStorageSection(
                        settings = settings,
                        cacheBytes = cacheBytes,
                        onEvent = onEvent,
                    )
                }

                item {
                    BackupSection(onEvent = onEvent)
                }

                item {
                    AutoUpdateSection(
                        settings = settings,
                        updateState = updateState,
                        onEvent = onEvent,
                    )
                }

                item {
                    FeedbackAndBugReportSection(onOpenBugDialog = { showBugReportDialog = true })
                }

                item {
                    SecurityInfoSection()
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun AppearanceSection(
    settings: AppSettings,
    onEvent: (SettingsEvent) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    SettingsSectionCard(title = "Aspetto & Material You", icon = Icons.Rounded.Palette) {
        Text(
            text = "Tema applicazione",
            color = cs.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { onEvent(SettingsEvent.ChangeThemeMode(mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                ) {
                    Text(mode.label, fontSize = 13.sp)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(Modifier.height(14.dp))
            SettingsSwitchRow(
                title = "Colori dinamici Material You",
                subtitle = "Adatta l'intera interfaccia ai colori dello sfondo di Android",
                checked = settings.dynamicColors,
                onCheckedChange = { onEvent(SettingsEvent.ToggleDynamicColors(it)) },
            )
        }
    }
}

@Composable
private fun PlayerPreferencesSection(
    settings: AppSettings,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsSectionCard(title = "Player & Salta Sigla Intelligente", icon = Icons.Rounded.FastForward) {
        SettingsSwitchRow(
            title = "Auto-Skip Sigla (OP)",
            subtitle = "Salta automaticamente l'intro rilevata da VixCloud / AniSkip (con tasto Annulla)",
            checked = settings.autoSkipIntro,
            onCheckedChange = { onEvent(SettingsEvent.ToggleAutoSkipIntro(it)) },
        )
        Spacer(Modifier.height(12.dp))
        SettingsSwitchRow(
            title = "Auto-Skip Outro (ED)",
            subtitle = "Salta automaticamente la sigla finale e prepara il prossimo episodio",
            checked = settings.autoSkipOutro,
            onCheckedChange = { onEvent(SettingsEvent.ToggleAutoSkipOutro(it)) },
        )
        Spacer(Modifier.height(12.dp))
        SettingsSwitchRow(
            title = "Picture-in-Picture automatico (PiP)",
            subtitle = "Continua in finestra flottante quando torni alla Home durante la visione",
            checked = settings.autoPipEnabled,
            onCheckedChange = { onEvent(SettingsEvent.ToggleAutoPip(it)) },
        )
    }
}

@Composable
private fun FavoriteNotificationsSection(
    settings: AppSettings,
    isCheckingFavorites: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val intervals = listOf(1, 3, 6, 12)
    SettingsSectionCard(title = "Notifiche Episodi & Nuove Stagioni", icon = Icons.Rounded.NotificationsActive) {
        SettingsSwitchRow(
            title = "Avvisami su nuovi episodi e nuove stagioni",
            subtitle = "Controlla solo gli anime con la campanella 🔔 attiva (Calendario / Scheda / Libreria) e salva in automatico eventuali nuove stagioni dei franchise già visti",
            checked = settings.notifyFavoriteEpisodes,
            onCheckedChange = { onEvent(SettingsEvent.ToggleFavoriteNotifications(it)) },
        )

        if (settings.notifyFavoriteEpisodes) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Frequenza controllo automatico",
                color = cs.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                intervals.forEachIndexed { idx, hours ->
                    SegmentedButton(
                        selected = settings.notifyCheckIntervalHours == hours,
                        onClick = { onEvent(SettingsEvent.ChangeNotifyIntervalHours(hours)) },
                        shape = SegmentedButtonDefaults.itemShape(idx, intervals.size),
                    ) {
                        Text("${hours}h", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { onEvent(SettingsEvent.CheckFavoriteEpisodesNow) },
            enabled = !isCheckingFavorites,
            shape = CircleShape,
        ) {
            if (isCheckingFavorites) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Controlla anime seguiti ora", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SuiteTvIntegrationSection(
    settings: AppSettings,
    isSuiteTvInstalled: Boolean,
    isImportingSuiteTv: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    SettingsSectionCard(title = "Integrazione Suite TV", icon = Icons.Rounded.Tv) {
        Text(
            text = if (isSuiteTvInstalled) {
                "App Suite TV rilevata: il tasto TV apre direttamente la scheda dell'anime nell'app nativa. Puoi anche esportare o importare la cronologia JSON."
            } else {
                "Traccia serie e anime su suitetv.app/it: esporta la tua cronologia per Suite TV oppure importa file JSON (Suite TV / Trakt / TV Time) in AW Anime."
            },
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        SettingsSwitchRow(
            title = "Tasto rapido Suite TV nel player",
            subtitle = "Mostra l'azione 'Segna su Suite TV' nella card di fine episodio",
            checked = settings.suiteTvQuickAction,
            onCheckedChange = { onEvent(SettingsEvent.ToggleSuiteTvQuickAction(it)) },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { onEvent(SettingsEvent.ExportSuiteTvHistory) },
                enabled = !isImportingSuiteTv,
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Esporta JSON", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            FilledTonalButton(
                onClick = { onEvent(SettingsEvent.ImportSuiteTvHistory) },
                enabled = !isImportingSuiteTv,
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                if (isImportingSuiteTv) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isImportingSuiteTv) "Importo..." else "Importa JSON",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { onEvent(SettingsEvent.OpenSuiteTv) },
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isSuiteTvInstalled) "Apri app Suite TV" else "Apri Suite TV Web",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Esportazione: in Suite TV vai su Impostazioni → Importa → Trakt. Importazione: seleziona un file JSON esportato da Suite TV, Trakt o TV Time.",
            color = cs.onSurfaceVariant.copy(alpha = 0.85f),
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun NetworkAndStorageSection(
    settings: AppSettings,
    cacheBytes: Long,
    onEvent: (SettingsEvent) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    SettingsSectionCard(title = "Rete, Download & Memoria", icon = Icons.Rounded.Wifi) {
        SettingsSwitchRow(
            title = "Download solo su Wi-Fi",
            subtitle = "Impedisce i download MP4 1080p su rete dati mobile",
            checked = settings.wifiOnlyDownloads,
            onCheckedChange = { onEvent(SettingsEvent.ToggleWifiOnlyDownloads(it)) },
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Link supportati AnimeUnity", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Apri direttamente in AW Anime i link animeunity.* ricevuti o toccati nel browser",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onEvent(SettingsEvent.OpenSupportedLinksSettings) },
                shape = CircleShape,
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Attiva")
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Cache locale app", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Immagini e APK temporanei: ${formatBytes(cacheBytes)}",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            FilledTonalButton(
                onClick = { onEvent(SettingsEvent.ClearCache) },
                shape = CircleShape,
            ) {
                Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Svuota")
            }
        }
    }
}

@Composable
private fun BackupSection(onEvent: (SettingsEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    SettingsSectionCard(title = "Backup & Ripristino (Locale)", icon = Icons.Rounded.CloudDone) {
        Text(
            text = "Esporta o ripristina Cronologia, Preferiti, Notifiche per anime e Impostazioni in un file JSON senza bisogno di account.",
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { onEvent(SettingsEvent.ExportBackup) },
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Esporta JSON", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            FilledTonalButton(
                onClick = { onEvent(SettingsEvent.ImportBackup) },
                shape = CircleShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Importa JSON", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AutoUpdateSection(
    settings: AppSettings,
    updateState: UpdateUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    SettingsSectionCard(title = "Aggiornamenti Automatici", icon = Icons.Rounded.SystemUpdate) {
        Column {
            Text(
                text = "Versione installata: v${BuildConfig.VERSION_NAME}",
                color = cs.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Canale: github.com/${BuildConfig.GITHUB_REPO}",
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        SettingsSwitchRow(
            title = "Controllo automatico all'avvio",
            subtitle = "Verifica nuove release APK firmate + SHA-256 obbligatorio su GitHub",
            checked = settings.autoCheckUpdates,
            onCheckedChange = { onEvent(SettingsEvent.ToggleAutoCheckUpdates(it)) },
        )

        updateState.statusMessage?.let { msg ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = msg,
                color = if (updateState.availableUpdate != null) cs.primary else cs.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = { onEvent(SettingsEvent.CheckUpdatesNow) },
                enabled = !updateState.isChecking && !updateState.isDownloading,
                shape = CircleShape,
            ) {
                if (updateState.isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Controlla ora")
            }

            if (updateState.availableUpdate != null) {
                Button(
                    onClick = { onEvent(SettingsEvent.OpenUpdateDialog) },
                    shape = CircleShape,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Installa ${updateState.availableUpdate.tagName}")
                }
            }
        }
    }
}

@Composable
private fun FeedbackAndBugReportSection(onOpenBugDialog: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    SettingsSectionCard(title = "Segnalazione Bug & Feedback", icon = Icons.Rounded.BugReport) {
        Text(
            text = "Hai trovato un problema o vuoi proporre una nuova funzione? Invia la segnalazione direttamente da qui in modo anonimo — non serve alcun account GitHub.",
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick = onOpenBugDialog,
            shape = CircleShape,
        ) {
            Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Segnala un bug o un'idea", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BugReportDialog(
    activeDomain: String,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmitAnonymous: (ReportKind, String, String) -> Unit,
    onShareReport: (ReportKind, String, String) -> Unit,
    onCopyReport: (ReportKind, String, String) -> Unit,
    onSubmitGitHub: (ReportKind, String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var selectedKind by remember { mutableStateOf(ReportKind.BUG) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    val canSubmit = !isSubmitting && (title.trim().length >= 3 || description.trim().length >= 5)
    val safeTitle = title.trim().ifBlank {
        if (selectedKind == ReportKind.BUG) "Segnalazione problema" else "Suggerimento"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
        title = {
            Text(
                text = "Segnala Bug o Proposta",
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReportKind.entries.forEachIndexed { idx, kind ->
                        SegmentedButton(
                            selected = selectedKind == kind,
                            onClick = { selectedKind = kind },
                            enabled = !isSubmitting,
                            shape = SegmentedButtonDefaults.itemShape(idx, ReportKind.entries.size),
                        ) {
                            Text(kind.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(110) },
                    enabled = !isSubmitting,
                    label = { Text("Titolo breve") },
                    placeholder = {
                        Text(
                            if (selectedKind == ReportKind.BUG) "Es. Errore apertura episodio..."
                            else "Es. Aggiungi filtro per anno..."
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(2000) },
                    enabled = !isSubmitting,
                    label = { Text("Descrizione") },
                    placeholder = { Text("Spiega cosa è successo o come riprodurlo...") },
                    minLines = 3,
                    maxLines = 5,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Invio anonimo senza account GitHub. Allegati tecnici: AW Anime v${BuildConfig.VERSION_NAME} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) • $activeDomain",
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(
                        onClick = { onShareReport(selectedKind, safeTitle, description) },
                        enabled = canSubmit,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Condividi", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { onCopyReport(selectedKind, safeTitle, description) },
                        enabled = canSubmit,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copia", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { onSubmitGitHub(selectedKind, safeTitle, description) },
                        enabled = canSubmit,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("GitHub", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmitAnonymous(selectedKind, safeTitle, description) },
                enabled = canSubmit,
                shape = CircleShape,
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = cs.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Invio...")
                } else {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Invia")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text("Annulla")
            }
        },
    )
}

@Composable
private fun SecurityInfoSection() {
    val cs = MaterialTheme.colorScheme
    SettingsSectionCard(title = "Sicurezza & Privacy", icon = Icons.Rounded.Security) {
        Text(
            text = "• Connessioni esclusivamente HTTPS (TLS di sistema, HTTP bloccato)\n" +
                "• DNS-over-HTTPS automatico con filtro anti-SSRF IPv4/IPv6 completo\n" +
                "• Aggiornamenti fail-closed con SHA-256 + firma certificato APK obbligatori\n" +
                "• Client isolato senza cookie verso servizi terzi (VixCloud / AniSkip)\n" +
                "• Zero pubblicità, zero tracker: dati salvati solo sul dispositivo",
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    color = cs.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
