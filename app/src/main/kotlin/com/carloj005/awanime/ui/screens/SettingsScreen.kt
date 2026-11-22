package com.carloj005.awanime.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carloj005.awanime.BuildConfig
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.ThemeMode
import com.carloj005.awanime.ui.UpdateUiState

@Composable
fun SettingsScreen(
    settings: AppSettings,
    updateState: UpdateUiState,
    onBack: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorsChange: (Boolean) -> Unit,
    onAutoSkipIntroChange: (Boolean) -> Unit,
    onAutoSkipOutroChange: (Boolean) -> Unit,
    onAutoCheckUpdatesChange: (Boolean) -> Unit,
    onCheckUpdatesNow: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
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
            // 1. Aspetto Material You 3 Expressive
            item {
                SettingsSectionCard(
                    title = "Aspetto & Material You",
                    icon = Icons.Rounded.Palette,
                ) {
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
                                onClick = { onThemeModeChange(mode) },
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
                            onCheckedChange = onDynamicColorsChange,
                        )
                    }
                }
            }

            // 2. Smart Skip OP / ED
            item {
                SettingsSectionCard(
                    title = "Player & Salta Sigla Intelligente",
                    icon = Icons.Rounded.FastForward,
                ) {
                    SettingsSwitchRow(
                        title = "Auto-Skip Sigla (OP)",
                        subtitle = "Salta automaticamente l'intro rilevata da VixCloud / AniSkip (con tasto Annulla)",
                        checked = settings.autoSkipIntro,
                        onCheckedChange = onAutoSkipIntroChange,
                    )
                    Spacer(Modifier.height(12.dp))
                    SettingsSwitchRow(
                        title = "Auto-Skip Outro (ED)",
                        subtitle = "Salta automaticamente la sigla finale e prepara il prossimo episodio",
                        checked = settings.autoSkipOutro,
                        onCheckedChange = onAutoSkipOutroChange,
                    )
                }
            }

            // 3. Aggiornamenti Automatici (GitHub Releases)
            item {
                SettingsSectionCard(
                    title = "Aggiornamenti Automatici",
                    icon = Icons.Rounded.SystemUpdate,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                    }

                    Spacer(Modifier.height(12.dp))
                    SettingsSwitchRow(
                        title = "Controllo automatico all'avvio",
                        subtitle = "Verifica nuove release APK su GitHub quando apri l'app",
                        checked = settings.autoCheckUpdates,
                        onCheckedChange = onAutoCheckUpdatesChange,
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
                            onClick = onCheckUpdatesNow,
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
                                onClick = onOpenUpdateDialog,
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
        }
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
