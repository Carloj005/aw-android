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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
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
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.formatTimeMs
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
fun HistoryScreen(
    history: List<WatchHistoryEntry>,
    onResume: (WatchHistoryEntry) -> Unit,
    onOpenDetail: (AnimeItem) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MidnightBg)
            .padding(top = 12.dp),
    ) {
        Text(
            text = "Cronologia",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Nessun anime nella cronologia", color = TextSecondary, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Gli episodi che guardi appariranno qui", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(history, key = { it.uniqueKey }) { entry ->
                    HistoryCard(
                        entry = entry,
                        onResume = { onResume(entry) },
                        onOpenDetail = { onOpenDetail(entry.anime) },
                        onRemove = { onRemove(entry.uniqueKey) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: WatchHistoryEntry,
    onResume: () -> Unit,
    onOpenDetail: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
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
                model = entry.anime.displayImageUrl,
                contentDescription = entry.anime.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(72.dp)
                    .height(104.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceElevated),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.anime.title,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Rimuovi",
                            tint = ErrorRed.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgePill(
                        text = if (entry.anime.isDub) "ITA" else "SUB",
                        backgroundColor = if (entry.anime.isDub) BadgeItaCoral else BadgeSubCyan,
                    )
                    val epLabel = if (entry.completed) {
                        "Ep. ${entry.currentEpisodeNum} completato"
                    } else {
                        "Ep. ${entry.currentEpisodeNum} • ${formatTimeMs(entry.progressMs)}" +
                            if (entry.durationMs > 0L) " / ${formatTimeMs(entry.durationMs)}" else ""
                    }
                    Text(text = epLabel, color = TextSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { entry.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (entry.completed) BadgeGreen else AccentViolet,
                    trackColor = SurfaceElevated,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onResume,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                    modifier = Modifier.height(34.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (entry.completed) "Prossimo episodio" else "Riprendi",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
