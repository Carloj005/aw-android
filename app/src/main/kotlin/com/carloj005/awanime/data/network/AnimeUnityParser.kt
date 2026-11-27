package com.carloj005.awanime.data.network

import com.carloj005.awanime.data.model.AnimeItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Parser dedicato alla conversione dei payload JSON di AnimeUnity (Inertia e REST API)
 * nei modelli di dominio immutabili [AnimeItem].
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

    fun mapStatus(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "ongoing", "in corso" -> "In corso"
        "completed", "terminato", "finito" -> "Finito"
        "upcoming", "not_yet_released", "non rilasciato" -> "Non rilasciato"
        else -> "Sconosciuto"
    }

    fun pickPreferredSubLanguage(obj: JsonObject): String {
        val variants = obj["variants"] as? JsonArray ?: return "jpn"
        val nonIta = variants.mapNotNull { it as? JsonObject }
            .mapNotNull { it.str("language")?.lowercase() }
            .filter { it != "ita" }
        return if ("jpn" in nonIta) "jpn" else (nonIta.firstOrNull() ?: "jpn")
    }

    fun pickEpisodeVideo(videos: JsonArray, wantDub: Boolean): JsonObject? {
        val objs = videos.mapNotNull { it as? JsonObject }
        val visible = objs.filter { it.boolVal("is_hidden") != true }.ifEmpty { objs }
        if (visible.isEmpty()) return null
        if (wantDub) {
            return visible.firstOrNull { it.str("language")?.lowercase() == "ita" }
        }
        val nonIta = visible.filter { it.str("language")?.lowercase() != "ita" }
        return nonIta.firstOrNull { it.str("language")?.lowercase() == "jpn" } ?: nonIta.firstOrNull()
    }

    fun parseAnimeVariants(obj: JsonObject): List<AnimeItem> {
        val variants = (obj["variants"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        if (variants.isEmpty()) {
            return listOf(parseSingleAnime(obj, variantLang = "jpn", forceDub = false))
        }
        val ita = variants.firstOrNull { it.str("language")?.lowercase() == "ita" }
        val nonIta = variants.filter { it.str("language")?.lowercase() != "ita" }
        val sub = nonIta.firstOrNull { it.str("language")?.lowercase() == "jpn" } ?: nonIta.firstOrNull()

        val result = mutableListOf<AnimeItem>()
        if (sub != null) {
            result.add(
                parseSingleAnime(
                    obj = obj,
                    variantLang = sub.str("language")?.lowercase() ?: "jpn",
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
        val displayTitle = if (forceDub && "(ita)" !in rawTitle.lowercase()) "$rawTitle (ITA)" else rawTitle

        val variants = (obj["variants"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val hasDub = variants.any { it.str("language")?.lowercase() == "ita" } || forceDub
        val hasSub = variants.any { it.str("language")?.lowercase() != "ita" } || !forceDub

        val matchedVariant = variants.firstOrNull { it.str("language")?.lowercase() == variantLang }
            ?: if (forceDub) {
                variants.firstOrNull { it.str("language")?.lowercase() == "ita" }
            } else {
                variants.firstOrNull { it.str("language")?.lowercase() != "ita" }
            }

        val releasedCount = matchedVariant?.intVal("episodes_count")
            ?: variants.mapNotNull { it.intVal("episodes_count") }.maxOrNull()
            ?: obj.intVal("total_episodes")
            ?: 0

        val statusRaw = matchedVariant?.str("status") ?: obj.str("default_status") ?: obj.str("status")
        val images = obj["images"] as? JsonObject
        val genres = extractNamedList(obj, "genres")
        val studios = extractNamedList(obj, "studios")

        val rawType = obj.str("type")?.lowercase().orEmpty()
        val rawSeason = obj.str("season")?.lowercase().orEmpty()

        return AnimeItem(
            id = id,
            slug = slug,
            title = displayTitle,
            titleJa = obj.str("title_ja"),
            titleIt = obj.str("title_it"),
            type = TYPE_LABELS[rawType] ?: rawType.uppercase().ifEmpty { "TV" },
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
            audioLabel = if (forceDub) "Italiano" else (LANGUAGE_LABELS[variantLang] ?: variantLang.uppercase()),
            releasedEpisodes = releasedCount,
            latestEpisodeNum = latestEpNum,
            latestVideoId = latestVideoId,
            hasSubVariant = hasSub,
            hasDubVariant = hasDub,
        )
    }

    private fun extractNamedList(obj: JsonObject, key: String): List<String> {
        return (obj[key] as? JsonArray)?.mapNotNull { element ->
            (element as? JsonObject)?.str("name")?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }
}
