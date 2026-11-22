package com.carloj005.awanime.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.carloj005.awanime.ui.theme.AccentViolet
import com.carloj005.awanime.ui.theme.BadgeGreen
import com.carloj005.awanime.ui.theme.BadgeItaCoral
import com.carloj005.awanime.ui.theme.BadgeSubCyan
import com.carloj005.awanime.ui.theme.MidnightBg
import com.carloj005.awanime.ui.theme.StarAmber
import com.carloj005.awanime.ui.theme.SurfaceDark
import com.carloj005.awanime.ui.theme.SurfaceElevated
import com.carloj005.awanime.ui.theme.TextPrimary
import com.carloj005.awanime.ui.theme.TextSecondary

@Composable
fun BadgePill(
    text: String,
    backgroundColor: Color,
    contentColor: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun AnimePosterCard(
    anime: AnimeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .background(SurfaceElevated),
            ) {
                AsyncImage(
                    model = anime.displayImageUrl,
                    contentDescription = anime.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                // Sfumatura inferiore per leggibilità dei badge
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xE60B0D14)),
                            )
                        ),
                )

                // Badge Audio (ITA / SUB) in alto a sinistra
                BadgePill(
                    text = if (anime.isDub) "ITA" else "SUB",
                    backgroundColor = if (anime.isDub) BadgeItaCoral else BadgeSubCyan,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )

                // Badge Voto in alto a destra
                anime.score?.let { score ->
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC0B0D14))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = StarAmber,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = score,
                            color = TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Badge Episodio / Anno in basso
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val epLabel = when {
                        !anime.latestEpisodeNum.isNullOrBlank() -> "Ep. ${anime.latestEpisodeNum}"
                        anime.releasedEpisodes > 0 -> "${anime.releasedEpisodes} ep"
                        else -> anime.type
                    }
                    BadgePill(
                        text = epLabel,
                        backgroundColor = AccentViolet.copy(alpha = 0.92f),
                    )
                    anime.year?.let { yr ->
                        Text(
                            text = yr.toString(),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Text(
                text = anime.title,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun ContinueWatchingCard(
    entry: WatchHistoryEntry,
    onResume: () -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .width(220.dp)
            .clickable(onClick = onResume),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(122.dp)
                    .background(SurfaceElevated),
            ) {
                AsyncImage(
                    model = entry.anime.displayBannerUrl,
                    contentDescription = entry.anime.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0x330B0D14), Color(0xCC0B0D14)),
                            )
                        ),
                )

                // Pulsante play centrale
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(AccentViolet.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Riprendi",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                BadgePill(
                    text = if (entry.anime.isDub) "ITA" else "SUB",
                    backgroundColor = if (entry.anime.isDub) BadgeItaCoral else BadgeSubCyan,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )

                val statusText = if (entry.completed) {
                    "Ep. ${entry.currentEpisodeNum} ✓"
                } else {
                    "Ep. ${entry.currentEpisodeNum} • ${formatTimeMs(entry.progressMs)}"
                }
                BadgePill(
                    text = statusText,
                    backgroundColor = if (entry.completed) BadgeGreen.copy(alpha = 0.9f) else Color(0xCC0B0D14),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 10.dp),
                )

                LinearProgressIndicator(
                    progress = { entry.progressFraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp),
                    color = if (entry.completed) BadgeGreen else AccentViolet,
                    trackColor = Color(0x55FFFFFF),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDetail)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.anime.title,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun ErrorRetryBox(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Riprova")
        }
    }
}

@Composable
fun LoadingPlaceholderBox(
    text: String = "Caricamento...",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MidnightBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(color = AccentViolet)
            Spacer(Modifier.height(12.dp))
            Text(text = text, color = TextSecondary, fontSize = 14.sp)
        }
    }
}
