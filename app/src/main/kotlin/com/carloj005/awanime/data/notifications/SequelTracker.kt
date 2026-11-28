package com.carloj005.awanime.data.notifications

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.DiscoveredSequel
import com.carloj005.awanime.data.network.SearchUtils
import java.util.Locale

data class UserFranchiseProfile(
    val knownIds: Set<String>,
    val prefersSub: Boolean,
    val prefersDub: Boolean,
    val maxYear: Int,
    val maxPart: Int,
    val maxId: Int,
)

/**
 * Pure franchise & sequel matcher.
 * - Resolves the immediate next season from AnimeUnity's `relatedAnimes` list.
 * - Detects newly announced / airing sequels in Calendar and Latest feeds for franchises the user
 *   already watched, finished, or saved in Favorites (e.g. Solo Leveling S1 -> S2 -> future S3),
 *   strictly honoring the user's preferred audio language (SUB vs ITA) for each franchise.
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
     * Builds per-franchise language & latest-season profiles from the user's library (Favorites + History).
     */
    fun buildUserFranchiseProfiles(userLibrary: List<AnimeItem>): Map<String, UserFranchiseProfile> {
        if (userLibrary.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, UserFranchiseProfile>()
        for (item in userLibrary) {
            val fKey = SearchUtils.extractFranchiseKey(item.title)
            if (fKey.length < 4) continue
            val yr = item.year ?: 0
            val part = SearchUtils.extractPartNumber(item.title)
            val idInt = item.id.toIntOrNull() ?: 0
            val prev = result[fKey]
            if (prev == null) {
                result[fKey] = UserFranchiseProfile(
                    knownIds = if (item.id.isNotBlank()) setOf(item.id) else emptySet(),
                    prefersSub = !item.isDub,
                    prefersDub = item.isDub,
                    maxYear = yr,
                    maxPart = part,
                    maxId = idInt,
                )
            } else {
                result[fKey] = prev.copy(
                    knownIds = if (item.id.isNotBlank()) prev.knownIds + item.id else prev.knownIds,
                    prefersSub = prev.prefersSub || !item.isDub,
                    prefersDub = prev.prefersDub || item.isDub,
                    maxYear = maxOf(prev.maxYear, yr),
                    maxPart = maxOf(prev.maxPart, part),
                    maxId = maxOf(prev.maxId, idInt),
                )
            }
        }
        return result
    }

    /**
     * Returns true ONLY when a calendar candidate is genuinely a newer sequel of a franchise the
     * user follows AND matches the user's preferred audio language (SUB vs ITA) for that franchise.
     *
     * Crucially returns false if:
     * - The same `anime.id` is already in the user's library (e.g. user watches JoJo Part 7 ITA ->
     *   JoJo Part 7 SUB has the same ID and must NOT appear in "I miei").
     * - The candidate's language (SUB/ITA) does not match what the user watches for that franchise.
     */
    fun isCalendarFranchiseSequel(
        candidate: AnimeItem,
        profiles: Map<String, UserFranchiseProfile>,
        allKnownAnimeIds: Set<String>,
    ): Boolean {
        if (candidate.id.isBlank()) return false
        if (candidate.id in allKnownAnimeIds) return false

        val fKey = SearchUtils.extractFranchiseKey(candidate.title)
        if (fKey.length < 4) return false
        val profile = profiles[fKey] ?: return false
        if (candidate.id in profile.knownIds) return false

        val languageMatches = (candidate.isDub && profile.prefersDub) ||
            (!candidate.isDub && profile.prefersSub)
        if (!languageMatches) return false

        val candYear = candidate.year ?: 0
        val candPart = SearchUtils.extractPartNumber(candidate.title)
        val candId = candidate.id.toIntOrNull() ?: 0

        return when {
            profile.maxYear > 0 && candYear > profile.maxYear -> true
            profile.maxYear > 0 && candYear == profile.maxYear && candPart > profile.maxPart -> true
            profile.maxYear == 0 && candId > profile.maxId -> true
            candYear == 0 && candId > profile.maxId -> true
            else -> false
        }
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

        val profiles = buildUserFranchiseProfiles(userLibrary)
        val allKnownIds = userLibrary.mapNotNull { it.id.takeIf { id -> id.isNotBlank() } }.toSet()

        val latestByFranchise = LinkedHashMap<String, AnimeItem>()
        for (item in userLibrary) {
            val fKey = SearchUtils.extractFranchiseKey(item.title)
            if (fKey.length < 4) continue
            val prev = latestByFranchise[fKey]
            if (prev == null) {
                latestByFranchise[fKey] = item
            } else {
                val yr = item.year ?: 0
                val prevYr = prev.year ?: 0
                val isNewer = yr > prevYr ||
                    (yr == prevYr && SearchUtils.extractPartNumber(item.title) > SearchUtils.extractPartNumber(prev.title)) ||
                    (yr == prevYr && (item.id.toIntOrNull() ?: 0) > (prev.id.toIntOrNull() ?: 0))
                if (isNewer) latestByFranchise[fKey] = item
            }
        }

        val discovered = mutableListOf<DiscoveredSequel>()
        val emittedKeys = HashSet<String>()

        for (cand in feedCandidates) {
            if (cand.id.isBlank()) continue
            if (cand.uniqueKey in knownUserKeys || cand.uniqueKey in alreadyDiscoveredKeys) continue
            if (!emittedKeys.add(cand.uniqueKey)) continue

            if (!isCalendarFranchiseSequel(cand, profiles, allKnownIds)) continue
            val fKey = SearchUtils.extractFranchiseKey(cand.title)
            val parent = latestByFranchise[fKey] ?: continue
            discovered.add(DiscoveredSequel(parentAnime = parent, newSeason = cand))
        }

        return discovered
    }

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
