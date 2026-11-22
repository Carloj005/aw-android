package com.carloj005.awanime.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.ui.SearchUiState
import com.carloj005.awanime.ui.components.BadgePill
import com.carloj005.awanime.ui.components.ErrorRetryBox
import com.carloj005.awanime.ui.components.LoadingPlaceholderBox
import com.carloj005.awanime.ui.theme.BadgeItaCoral
import com.carloj005.awanime.ui.theme.BadgeSubBlue
import com.carloj005.awanime.ui.theme.StarAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    onAnimeClick: (AnimeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cs.background)
            .padding(top = 12.dp),
    ) {
        Text(
            text = "Cerca Anime",
            color = cs.onBackground,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text("Es. JoJo, Dragon Ball, Solo Leveling...", color = cs.onSurfaceVariant) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = cs.primary) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Pulisci", tint = cs.onSurfaceVariant)
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cs.surfaceContainerHigh,
                unfocusedContainerColor = cs.surfaceContainer,
                focusedBorderColor = cs.primary,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = cs.onSurface,
                unfocusedTextColor = cs.onSurface,
                cursorColor = cs.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboard?.hide()
                    onSearchSubmit(state.query)
                },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when {
            state.isLoading -> {
                LoadingPlaceholderBox(text = "Ricerca in corso (ordine cronologico)...")
            }

            state.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorRetryBox(message = state.error, onRetry = { onSearchSubmit(state.query) })
                }
            }

            state.hasSearched && state.results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nessun risultato trovato per \"${state.query}\"",
                        color = cs.onSurfaceVariant,
                        fontSize = 15.sp,
                    )
                }
            }

            !state.hasSearched -> {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (state.recentQueries.isNotEmpty()) {
                        Text(
                            text = "Ricerche recenti",
                            color = cs.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.recentQueries.forEach { q ->
                                AssistChip(
                                    onClick = {
                                        keyboard?.hide()
                                        onSearchSubmit(q)
                                    },
                                    label = { Text(q, color = cs.onSurface) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.History,
                                            contentDescription = null,
                                            tint = cs.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    shape = CircleShape,
                                    colors = AssistChipDefaults.assistChipColors(containerColor = cs.surfaceContainerHigh),
                                    border = null,
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
                    ) {
                        Text(
                            text = "Suggerimento: la ricerca tollera anche errori di battitura (es. \"jojo steal ball run\") e ordina automaticamente tutte le stagioni dalla prima all'ultima.",
                            color = cs.onSurfaceVariant,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }

            else -> {
                Text(
                    text = "${state.results.size} risultati trovati (in ordine cronologico)",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.results, key = { it.uniqueKey }) { anime ->
                        SearchResultRow(anime = anime, onClick = { onAnimeClick(anime) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    anime: AnimeItem,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = anime.displayImageUrl,
                contentDescription = anime.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(74.dp)
                    .height(106.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surfaceContainerHigh),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anime.title,
                    color = cs.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgePill(
                        text = if (anime.isDub) "ITA" else anime.audioLabel.uppercase().take(3),
                        backgroundColor = if (anime.isDub) BadgeItaCoral else BadgeSubBlue,
                    )
                    BadgePill(
                        text = anime.type,
                        backgroundColor = cs.surfaceContainerHigh,
                        contentColor = cs.onSurfaceVariant,
                    )
                    anime.year?.let { yr ->
                        Text(
                            text = if (anime.season.isNotBlank()) "${anime.season} $yr" else yr.toString(),
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val epsStr = when {
                        anime.releasedEpisodes > 0 && anime.totalEpisodes != null && anime.totalEpisodes > 0 ->
                            "${anime.releasedEpisodes}/${anime.totalEpisodes} ep"
                        anime.releasedEpisodes > 0 -> "${anime.releasedEpisodes} ep"
                        else -> anime.status
                    }
                    Text(text = epsStr, color = cs.onSurfaceVariant, fontSize = 12.sp)

                    anime.score?.let { sc ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                tint = StarAmber,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(text = sc, color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
