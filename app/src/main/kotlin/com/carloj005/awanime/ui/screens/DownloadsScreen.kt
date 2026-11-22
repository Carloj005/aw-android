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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.carloj005.awanime.ui.theme.AccentViolet
import com.carloj005.awanime.ui.theme.BadgeGreen
import com.carloj005.awanime.ui.theme.BadgeItaCoral
import com.carloj005.awanime.ui.theme.BadgeSubCyan
import com.carloj005.awanime.ui.theme.ErrorRed
import com.carloj005.awanime.ui.theme.MidnightBg
import com.carloj005.awanime.ui.theme.SurfaceDark
import com.carloj005.awanime.ui.theme.SurfaceElevated
import com.carloj005.awanime.ui.theme.TextMuted
import com.carloj005.awanime.ui.theme.TextPrimary
import com.carloj005.awanime.ui.theme.TextSecondary

@Composable
fun DownloadsScreen(
    downloads: List<DownloadEntry>,
    onPlayOffline: (DownloadEntry) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalStored = downloads
        .filter { it.status == DownloadStatus.COMPLETED }
        .sumOf { it.totalBytes }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MidnightBg)
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
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            if (totalStored > 0L) {
                BadgePill(
                    text = formatBytes(totalStored),
                    backgroundColor = SurfaceElevated,
                    contentColor = TextSecondary,
                )
            }
        }

        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Downloading,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Nessun episodio scaricato", color = TextSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Apri un anime e tocca l'icona download accanto a un episodio", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(downloads, key = { it.key }) { entry ->
                    DownloadItemCard(
                        entry = entry,
                        onPlay = { onPlayOffline(entry) },
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
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = entry.status == DownloadStatus.COMPLETED, onClick = onPlay),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.posterUrl,
                contentDescription = entry.animeTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .height(92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceElevated),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.animeTitle,
                    color = TextPrimary,
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
                        backgroundColor = if (entry.isDub) BadgeItaCoral else BadgeSubCyan,
                    )
                    Text(
                        text = "Episodio ${entry.episodeNum}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(6.dp))

                when (entry.status) {
                    DownloadStatus.QUEUED -> {
                        Text("In coda...", color = TextSecondary, fontSize = 12.sp)
                    }

                    DownloadStatus.DOWNLOADING -> {
                        val sizeText = if (entry.totalBytes > 0L) {
                            "${formatBytes(entry.bytesDownloaded)} / ${formatBytes(entry.totalBytes)} (${(entry.progressFraction * 100).toInt()}%)"
                        } else {
                            formatBytes(entry.bytesDownloaded)
                        }
                        Text(sizeText, color = AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { entry.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentViolet,
                            trackColor = SurfaceElevated,
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
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = onPlay,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BadgeGreen),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Guarda Offline", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    DownloadStatus.FAILED -> {
                        Text(
                            text = entry.errorMessage ?: "Download fallito",
                            color = ErrorRed,
                            fontSize = 12.sp,
                            maxLines = 2,
                        )
                    }
                }
            }

            if (entry.status == DownloadStatus.DOWNLOADING || entry.status == DownloadStatus.QUEUED) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, contentDescription = "Annulla", tint = TextSecondary)
                }
            } else {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Elimina", tint = ErrorRed)
                }
            }
        }
    }
}
