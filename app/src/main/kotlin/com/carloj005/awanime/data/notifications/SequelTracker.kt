package com.carloj005.awanime.data.notifications

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.DiscoveredSequel
import com.carloj005.awanime.data.network.SearchUtils
import java.util.Locale

/**
 * Pure franchise & sequel matcher.
 * - Resolves the immediate next season from AnimeUnity's `relatedAnimes` list.
 * - Detects newly announced / airing sequels in Calendar and Latest feeds for franchises the user
 *   already watched, finished, or saved in Favorites (e.g. Solo Leveling S1 -> S2 -> future S3).
 */
object SequelTracker {

    private val SEASON_QUARTER = mapOf(
        "inverno" to 1, "winter" to 1,
        "primavera" to 2, "spring" to 2,
        "estate" to 3, "summer" to 3,
        "autunno" to 4, "autumn" to 4, "fall" to 4,
    )

    private val NON_MAINLINE_TYPES = setOf("ova", "special", "tv short", "tv_short")

    /**
     * Given the current anime and AnimeUnity's `relatedAnimes` list, returns the immediate next
     * chronological mainline season/sequel (matching SUB/ITA language preference when available).
     */
    fun findNextSeason(
        current: AnimeItem,
        relatedAnimes: List<AnimeItem>,
    ): AnimeItem? {
        if (relatedAnimes.isEmpty()) return null

        val distinctById = LinkedHashMap<String, AnimeItem>()
        for (rel in relatedAnimes) {
            if (rel.id.isBlank()) continue
            val preferred = if (rel.isDub == current.isDub) rel else null
            if (preferred != null) {
                distinctById[rel.id] = preferred
            } else {
                distinctById.putIfAbsent(rel.id, rel)
            }
        }
        distinctById.putIfAbsent(current.id, current)

        val sorted = distinctById.values.sortedWith(
            compareBy<AnimeItem>(
                { it.year ?: 9999 },
                { SEASON_QUARTER[it.season.lowercase(Locale.ROOT).trim()] ?: 3 },
                { SearchUtils.extractPartNumber(it.title) },
                { it.id.toIntOrNull() ?: Int.MAX_VALUE },
            )
        )

        val currentIdx = sorted.indexOfFirst { it.id == current.id }
        if (currentIdx < 0 || currentIdx >= sorted.lastIndex) return null

        val afterCurrent = sorted.subList(currentIdx + 1, sorted.size)
        // Prefer mainline TV/ONA/Movie sequel over minor OVA/Special if both exist
        val candidate = afterCurrent.firstOrNull {
            it.type.lowercase(Locale.ROOT) !in NON_MAINLINE_TYPES
        } ?: afterCurrent.firstOrNull() ?: return null

        return adaptLanguageVariant(candidate, wantDub = current.isDub)
    }

    /**
     * Detects new seasons / sequels appearing in live feeds (Calendar or Latest Episodes) that
     * belong to a franchise the user already watched or favorited, excluding items already known
     * or previously discovered.
     */
    fun detectNewSeasonsInFeed(
        userLibrary: List<AnimeItem>,
        feedCandidates: List<AnimeItem>,
        knownUserKeys: Set<String>,
        alreadyDiscoveredKeys: Set<String>,
    ): List<DiscoveredSequel> {
        if (userLibrary.isEmpty() || feedCandidates.isEmpty()) return emptyList()

        data class FranchiseAnchor(
            val latestAnime: AnimeItem,
            val maxYear: Int,
            val maxPart: Int,
            val maxId: Int,
            val knownIds: MutableSet<String>,
            val prefersDub: Boolean,
            val prefersSub: Boolean,
        )

        val anchors = LinkedHashMap<String, FranchiseAnchor>()
        for (item in userLibrary) {
            val fKey = SearchUtils.extractFranchiseKey(item.title)
            if (fKey.length < 4) continue
            val yr = item.year ?: 0
            val part = SearchUtils.extractPartNumber(item.title)
            val idInt = item.id.toIntOrNull() ?: 0
            val prev = anchors[fKey]
            if (prev == null) {
                anchors[fKey] = FranchiseAnchor(
                    latestAnime = item,
                    maxYear = yr,
                    maxPart = part,
                    maxId = idInt,
                    knownIds = mutableSetOf(item.id),
                    prefersDub = item.isDub,
                    prefersSub = !item.isDub,
                )
            } else {
                prev.knownIds.add(item.id)
                val isNewer = yr > prev.maxYear || (yr == prev.maxYear && (part > prev.maxPart || idInt > prev.maxId))
                anchors[fKey] = prev.copy(
                    latestAnime = if (isNewer) item else prev.latestAnime,
                    maxYear = maxOf(prev.maxYear, yr),
                    maxPart = maxOf(prev.maxPart, part),
                    maxId = maxOf(prev.maxId, idInt),
                    prefersDub = prev.prefersDub || item.isDub,
                    prefersSub = prev.prefersSub || !item.isDub,
                )
            }
        }

        val discovered = mutableListOf<DiscoveredSequel>()
        val emittedKeys = HashSet<String>()

        for (cand in feedCandidates) {
            if (cand.id.isBlank()) continue
            if (cand.uniqueKey in knownUserKeys || cand.uniqueKey in alreadyDiscoveredKeys) continue
            if (!emittedKeys.add(cand.uniqueKey)) continue

            val candFKey = SearchUtils.extractFranchiseKey(cand.title)
            if (candFKey.length < 4) continue
            val anchor = anchors[candFKey] ?: continue
            if (cand.id in anchor.knownIds) continue

            // Match user's preferred audio language for this franchise
            val languageMatches = (cand.isDub && anchor.prefersDub) || (!cand.isDub && anchor.prefersSub)
            if (!languageMatches) continue

            val candYear = cand.year ?: 0
            val candPart = SearchUtils.extractPartNumber(cand.title)
            val candId = cand.id.toIntOrNull() ?: 0

            val isNewerSeason = when {
                anchor.maxYear > 0 && candYear > anchor.maxYear -> true
                anchor.maxYear > 0 && candYear == anchor.maxYear && candPart > anchor.maxPart -> true
                anchor.maxYear == 0 && candId > anchor.maxId -> true
                candYear == 0 && candId > anchor.maxId -> true
                else -> false
            }
            if (isNewerSeason) {
                discovered.add(DiscoveredSequel(parentAnime = anchor.latestAnime, newSeason = cand))
            }
        }

        return discovered
    }

    /**
     * Checks if a calendar/feed candidate belongs to any franchise currently in the user's library
     * (used to highlight upcoming sequels directly inside the personalized Calendar tab).
     */
    fun matchesUserFranchise(
        candidate: AnimeItem,
        userFranchiseKeys: Set<String>,
    ): Boolean {
        val fKey = SearchUtils.extractFranchiseKey(candidate.title)
        return fKey.length >= 4 && fKey in userFranchiseKeys
    }

    private fun adaptLanguageVariant(anime: AnimeItem, wantDub: Boolean): AnimeItem {
        if (anime.isDub == wantDub) return anime
        val canUseRequested = if (wantDub) anime.hasDubVariant else anime.hasSubVariant
        if (!canUseRequested) return anime
        val baseTitle = SearchUtils.stripDubTags(anime.title)
        return anime.copy(
            title = if (wantDub) "$baseTitle (ITA)" else baseTitle,
            isDub = wantDub,
            audioLabel = if (wantDub) "Italiano" else "Giapponese",
        )
    }
}
