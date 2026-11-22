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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.formatBytes
import com.carloj005.awanime.ui.components.BadgePill
import com.carloj005.awanime.ui.theme.BadgeGreen
import com.carloj005.awanime.ui.theme.BadgeItaCoral
import com.carloj005.awanime.ui.theme.BadgeSubBlue

@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntry>,
    onPlayOffline: (DownloadEntry) -> Unit,
    onRetry: (DownloadEntry) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val totalStored = downloads
        .filter { it.status == DownloadStatus.COMPLETED }
        .sumOf { it.totalBytes }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cs.background)
            .padding(top = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Download Offline",
                color = cs.onBackground,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            if (totalStored > 0L) {
                BadgePill(
                    text = formatBytes(totalStored),
                    backgroundColor = cs.secondaryContainer,
                    contentColor = cs.onSecondaryContainer,
                )
            }
        }

        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Downloading,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(60.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Nessun episodio scaricato", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Apri un anime e tocca l'icona download accanto a un episodio",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(downloads, key = { it.key }) { entry ->
                    DownloadItemCard(
                        entry = entry,
                        onPlay = { onPlayOffline(entry) },
                        onRetry = { onRetry(entry) },
                        onCancel = { onCancel(entry.key) },
                        onDelete = { onDelete(entry.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    entry: DownloadEntry,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.status == DownloadStatus.COMPLETED, onClick = onPlay),
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
                model = entry.posterUrl,
                contentDescription = entry.animeTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(66.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surfaceContainerHigh),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.animeTitle,
                    color = cs.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgePill(
                        text = if (entry.isDub) "ITA" else "SUB",
                        backgroundColor = if (entry.isDub) BadgeItaCoral else BadgeSubBlue,
                    )
                    Text(
                        text = "Episodio ${entry.episodeNum}",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(6.dp))

                when (entry.status) {
                    DownloadStatus.QUEUED -> {
                        Text("In coda...", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }

                    DownloadStatus.DOWNLOADING -> {
                        val sizeText = if (entry.totalBytes > 0L) {
                            "${formatBytes(entry.bytesDownloaded)} / ${formatBytes(entry.totalBytes)} (${(entry.progressFraction * 100).toInt()}%)"
                        } else {
                            formatBytes(entry.bytesDownloaded)
                        }
                        Text(sizeText, color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { entry.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                            color = cs.primary,
                            trackColor = cs.surfaceContainerHigh,
                        )
                    }

                    DownloadStatus.COMPLETED -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.DownloadDone,
                                contentDescription = null,
                                tint = BadgeGreen,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Pronto offline • ${formatBytes(entry.totalBytes)}",
                                color = BadgeGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onPlay,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = BadgeGreen, contentColor = Color.White),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Guarda Offline", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    DownloadStatus.FAILED -> {
                        Text(
                            text = entry.errorMessage ?: "Download fallito",
                            color = cs.error,
                            fontSize = 12.sp,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(6.dp))
                        FilledTonalButton(
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            shape = CircleShape,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Riprova", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (entry.status == DownloadStatus.DOWNLOADING || entry.status == DownloadStatus.QUEUED) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, contentDescription = "Annulla", tint = cs.onSurfaceVariant)
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Elimina", tint = cs.error)
                }
            }
        }
    }
}
