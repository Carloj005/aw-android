package com.carloj005.awanime.data.network

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AnimeStatus
import com.carloj005.awanime.data.model.CalendarDayGroup
import com.carloj005.awanime.data.model.CalendarEntry
import com.carloj005.awanime.data.model.EpisodeItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure JSON parser converting AnimeUnity payloads (Inertia, REST, and /api/calendar)
 * into immutable domain models.
 */
object AnimeUnityParser {

    private val SEASON_LABELS = mapOf(
        "winter" to "Inverno", "spring" to "Primavera", "summer" to "Estate",
        "fall" to "Autunno", "autumn" to "Autunno",
        "inverno" to "Inverno", "primavera" to "Primavera", "estate" to "Estate", "autunno" to "Autunno",
    )

    private val TYPE_LABELS = mapOf(
        "tv" to "TV", "movie" to "Movie", "ova" to "OVA",
        "ona" to "ONA", "tv_short" to "TV Short", "special" to "Special",
    )

    private val LANGUAGE_LABELS = mapOf(
        "ita" to "Italiano", "jpn" to "Giapponese", "chn" to "Cinese",
        "kor" to "Coreano", "eng" to "Inglese",
    )

    private val TIME_HH_MM_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN)
    private val ISO_TIME_FALLBACK_REGEX = Regex("""T(\d{2}:\d{2})""")

    fun mapStatus(raw: String?): String = AnimeStatus.fromRaw(raw).label

    fun pickPreferredSubLanguage(obj: JsonObject): String {
        val variants = obj["variants"] as? JsonArray ?: return "jpn"
        val nonIta = variants.mapNotNull { it as? JsonObject }
            .mapNotNull { it.str("language")?.lowercase(Locale.ROOT) }
            .filter { it != "ita" }
        return if ("jpn" in nonIta) "jpn" else (nonIta.firstOrNull() ?: "jpn")
    }

    fun pickEpisodeVideo(videos: JsonArray, wantDub: Boolean): JsonObject? {
        val objs = videos.mapNotNull { it as? JsonObject }
        val visible = objs.filter { it.boolVal("is_hidden") != true }.ifEmpty { objs }
        if (visible.isEmpty()) return null
        if (wantDub) {
            return visible.firstOrNull { it.str("language")?.lowercase(Locale.ROOT) == "ita" }
        }
        val nonIta = visible.filter { it.str("language")?.lowercase(Locale.ROOT) != "ita" }
        return nonIta.firstOrNull { it.str("language")?.lowercase(Locale.ROOT) == "jpn" } ?: nonIta.firstOrNull()
    }

    fun parseEpisodeObject(epObj: JsonObject?, isDub: Boolean): EpisodeItem? {
        if (epObj == null) return null
        val num = epObj.str("number")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val videos = epObj["videos"] as? JsonArray ?: return null
        val chosenVideo = pickEpisodeVideo(videos, wantDub = isDub) ?: return null
        val videoId = chosenVideo.str("id") ?: return null
        return EpisodeItem(
            episodeId = epObj.str("id").orEmpty(),
            number = num,
            videoId = videoId,
            language = chosenVideo.str("language")?.lowercase(Locale.ROOT) ?: if (isDub) "ita" else "jpn",
            runtimeSeconds = chosenVideo.intVal("runtime") ?: 0,
            scwsId = chosenVideo.str("scws_id").orEmpty(),
            isFiller = epObj.boolVal("is_filler") ?: false,
        )
    }

    fun parseLatestEpisodeItem(itemObj: JsonObject?): AnimeItem? {
        if (itemObj == null) return null
        val epObj = itemObj["episode"] as? JsonObject ?: return null
        val animeObj = epObj["anime"] as? JsonObject ?: return null
        val videoId = itemObj.str("id") ?: return null
        val epNum = epObj.str("number") ?: "1"
        val lang = itemObj.str("language")?.lowercase(Locale.ROOT) ?: "jpn"
        return parseSingleAnime(
            obj = animeObj,
            variantLang = lang,
            forceDub = lang == "ita",
            latestEpNum = epNum,
            latestVideoId = videoId,
        )
    }

    fun parseAnimeVariants(obj: JsonObject): List<AnimeItem> {
        val variants = (obj["variants"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (variants.isEmpty()) {
            return listOf(parseSingleAnime(obj, variantLang = "jpn", forceDub = false))
        }
        val ita = variants.firstOrNull { it.str("language")?.lowercase(Locale.ROOT) == "ita" }
        val nonIta = variants.filter { it.str("language")?.lowercase(Locale.ROOT) != "ita" }
        val sub = nonIta.firstOrNull { it.str("language")?.lowercase(Locale.ROOT) == "jpn" } ?: nonIta.firstOrNull()

        val result = mutableListOf<AnimeItem>()
        if (sub != null) {
            result.add(
                parseSingleAnime(
                    obj = obj,
                    variantLang = sub.str("language")?.lowercase(Locale.ROOT) ?: "jpn",
                    forceDub = false,
                )
            )
        }
        if (ita != null) {
            result.add(
                parseSingleAnime(
                    obj = obj,
                    variantLang = "ita",
                    forceDub = true,
                )
            )
        }
        return result.ifEmpty { listOf(parseSingleAnime(obj, variantLang = "jpn", forceDub = false)) }
    }

    fun parseSingleAnime(
        obj: JsonObject,
        variantLang: String,
        forceDub: Boolean,
        latestEpNum: String? = null,
        latestVideoId: String? = null,
    ): AnimeItem {
        val id = obj.str("id").orEmpty()
        val slug = obj.str("slug").orEmpty()
        val rawTitle = (obj.str("title") ?: obj.str("title_it") ?: obj.str("title_ja") ?: "Anime").trim()
        val displayTitle = if (forceDub && "(ita)" !in rawTitle.lowercase(Locale.ROOT)) "$rawTitle (ITA)" else rawTitle

        val variants = (obj["variants"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val hasDub = variants.any { it.str("language")?.lowercase(Locale.ROOT) == "ita" } || forceDub
        val hasSub = variants.any { it.str("language")?.lowercase(Locale.ROOT) != "ita" } || !forceDub

        val matchedVariant = variants.firstOrNull { it.str("language")?.lowercase(Locale.ROOT) == variantLang }
            ?: if (forceDub) {
                variants.firstOrNull { it.str("language")?.lowercase(Locale.ROOT) == "ita" }
            } else {
                variants.firstOrNull { it.str("language")?.lowercase(Locale.ROOT) != "ita" }
            }

        val releasedCount = matchedVariant?.intVal("episodes_count")
            ?: variants.mapNotNull { it.intVal("episodes_count") }.maxOrNull()
            ?: obj.intVal("total_episodes")
            ?: 0

        val statusRaw = matchedVariant?.str("status") ?: obj.str("default_status") ?: obj.str("status")
        val images = obj["images"] as? JsonObject
        val genres = extractNamedList(obj, "genres")
        val studios = extractNamedList(obj, "studios")

        val rawType = obj.str("type")?.lowercase(Locale.ROOT).orEmpty()
        val rawSeason = obj.str("season")?.lowercase(Locale.ROOT).orEmpty()

        return AnimeItem(
            id = id,
            slug = slug,
            title = displayTitle,
            titleJa = obj.str("title_ja"),
            titleIt = obj.str("title_it"),
            type = TYPE_LABELS[rawType] ?: rawType.uppercase(Locale.ROOT).ifEmpty { "TV" },
            season = SEASON_LABELS[rawSeason] ?: rawSeason,
            year = obj.intVal("year"),
            totalEpisodes = obj.intVal("total_episodes")?.takeIf { it > 0 },
            runtimeMinutes = obj.intVal("episode_runtime"),
            status = mapStatus(statusRaw),
            score = obj.str("score")?.takeIf { it.isNotBlank() && it != "0" },
            views = obj.longVal("views") ?: 0L,
            posterUrl = images?.str("poster"),
            coverUrl = images?.str("cover"),
            bannerUrl = images?.str("banner"),
            plot = obj.str("plot").orEmpty(),
            genres = genres,
            studios = studios,
            anilistId = obj.intVal("anilist_id") ?: 0,
            malId = obj.intVal("mal_id") ?: 0,
            isDub = forceDub,
            audioLabel = if (forceDub) "Italiano" else (LANGUAGE_LABELS[variantLang] ?: variantLang.uppercase(Locale.ROOT)),
            releasedEpisodes = releasedCount,
            latestEpisodeNum = latestEpNum,
            latestVideoId = latestVideoId,
            hasSubVariant = hasSub,
            hasDubVariant = hasDub,
        )
    }

    /**
     * Parses AnimeUnity's `/api/calendar` payload keyed by localized day headers
     * (e.g. `"Venerdì - Oggi"`, `"Sabato - 28/11"`, ..., `"Indeterminato"`).
     */
    fun parseCalendarPayload(root: JsonObject): List<CalendarDayGroup> {
        val groups = mutableListOf<CalendarDayGroup>()
        for ((dayHeader, element) in root) {
            val arr = element as? JsonArray ?: continue
            val entries = arr.mapNotNull { parseCalendarEntry(it as? JsonObject) }
            if (entries.isEmpty()) continue
            val lower = dayHeader.lowercase(Locale.ROOT)
            groups.add(
                CalendarDayGroup(
                    label = dayHeader.trim(),
                    shortLabel = shortenDayLabel(dayHeader),
                    isToday = "oggi" in lower,
                    isIndeterminate = "indeterminato" in lower,
                    entries = entries.sortedWith(
                        compareBy<CalendarEntry>(
                            { if (it.isAired) 1 else 0 },
                            { it.releaseTimeLabel ?: "99:99" },
                            { it.anime.title.lowercase(Locale.ROOT) },
                        )
                    ),
                )
            )
        }
        return groups
    }

    private fun parseCalendarEntry(entryObj: JsonObject?): CalendarEntry? {
        if (entryObj == null) return null
        val animeObj = entryObj["anime"] as? JsonObject ?: return null
        val isDub = entryObj.boolVal("isDub") ?: false
        val variantLang = if (isDub) "ita" else pickPreferredSubLanguage(animeObj)

        val airedList = (entryObj["airedEpisodes"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val latestAired = airedList.lastOrNull()
        val latestAiredNum = latestAired?.str("number")?.trim()?.takeIf { it.isNotEmpty() }
        val latestAiredVideoId = (latestAired?.get("videos") as? JsonArray)
            ?.let { pickEpisodeVideo(it, wantDub = isDub) }
            ?.str("id")

        val anime = parseSingleAnime(
            obj = animeObj,
            variantLang = variantLang,
            forceDub = isDub,
            latestEpNum = latestAiredNum,
            latestVideoId = latestAiredVideoId,
        )
        val releaseIso = entryObj.str("releaseTime")?.trim()?.takeIf { it.isNotEmpty() }
        return CalendarEntry(
            anime = anime,
            nextEpisodeNumber = entryObj.str("nextEpisodeNumber")?.trim()?.takeIf { it.isNotEmpty() },
            airedEpisodeNumber = latestAiredNum,
            releaseTimeIso = releaseIso,
            releaseTimeLabel = formatReleaseTimeLabel(releaseIso),
            airingDay = entryObj.str("airingDay")?.trim()?.lowercase(Locale.ROOT),
            isAired = entryObj.boolVal("isAired") ?: false,
            isLate = entryObj.boolVal("isLate") ?: false,
            isDub = isDub,
            isDailyRelease = entryObj.boolVal("isDailyRelease") ?: false,
            pausedUntil = entryObj.str("pausedUntil")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    internal fun formatReleaseTimeLabel(isoTimestamp: String?): String? {
        if (isoTimestamp.isNullOrBlank()) return null
        return runCatching {
            OffsetDateTime.parse(isoTimestamp)
                .atZoneSameInstant(ZoneId.systemDefault())
                .format(TIME_HH_MM_FORMATTER)
        }.getOrElse {
            ISO_TIME_FALLBACK_REGEX.find(isoTimestamp)?.groupValues?.getOrNull(1)
        }
    }

    internal fun shortenDayLabel(header: String): String {
        val clean = header.trim()
        val lower = clean.lowercase(Locale.ROOT)
        if ("oggi" in lower) return "Oggi"
        if ("indeterminato" in lower) return "TBA"
        val parts = clean.split("-").map { it.trim() }
        if (parts.size == 2) {
            val shortDay = parts[0].take(3).replaceFirstChar { it.uppercaseChar() }
            return "$shortDay ${parts[1]}"
        }
        return clean
    }

    private fun extractNamedList(obj: JsonObject, key: String): List<String> {
        return (obj[key] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonObject)?.str("name")?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }
}
