package com.carloj005.awanime.data.integrations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.network.SearchUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class SuiteTvExportResult(
    val jsonPayload: String,
    val showCount: Int,
    val episodeCount: Int,
)

data class ResolvedSuiteTvTarget(
    val tvdbId: Int? = null,
    val tvdbSeason: Int? = null,
    val tmdbId: Int? = null,
    val canonicalTitle: String? = null,
)

data class SuiteTvImportedShow(
    val title: String,
    val year: Int? = null,
    val tvdbId: Int? = null,
    val seasonNumber: Int = 1,
    val watchedEpisodes: Set<Int> = emptySet(),
    val lastWatchedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Integration with Suite TV (https://www.suitetv.app/it, native Android app `com.nicolasbordeaux.suitetv`).
 *
 * - Resolves AniList / MyAnimeList IDs to TheTVDB series IDs (via ARM / ani.zip) to open the exact
 *   show page directly inside the native Suite TV app via verified Android App Links
 *   (`https://www.suitetv.app/it/series/<tvdbId>`).
 * - Falls back cleanly to copying the stripped base series title (without arc/cour subtitles) +
 *   launching the native Suite TV app when no TVDB ID is available.
 * - Supports bidirectional JSON sync: Trakt v2 history export AND multi-format JSON import
 *   (Trakt v2 history JSON + Suite TV / TV Time Liberator `shows.json`).
 */
object SuiteTvIntegration {

    const val SUITE_TV_PACKAGE = "com.nicolasbordeaux.suitetv"
    const val SUITE_TV_HOME_URL = "https://www.suitetv.app/it"
    const val SUITE_TV_SEARCH_BASE_URL = "https://www.suitetv.app/it/search"
    const val SUITE_TV_SERIES_BASE_URL = "https://www.suitetv.app/it/series"

    private const val ARM_API_BASE = "https://arm.haglund.dev/api/v2/ids"
    private const val ANIZIP_API_BASE = "https://api.ani.zip/mappings"
    private const val RESOLVER_UA = "AW-Anime/1.4 (Android)"

    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)
    private val TVTIME_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val resolvedTargetCache = ConcurrentHashMap<String, ResolvedSuiteTvTarget>()

    private val SEASON_EXTRACTION_REGEXES = listOf(
        Regex("""\b(?:season|stagione)\s*(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE),
        Regex("""\bS(\d{1,2})\b"""),
    )

    private val TITLE_SUFFIX_CLEAN_REGEXES = listOf(
        Regex("""\s*[-–—:]?\s*\b(?:the\s+)?final\s+season\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–—:]?\s*\b(?:season|stagione)\s*\d+\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–—:]?\s*\b\d+(?:st|nd|rd|th)\s*(?:[-–—]\s*\d+(?:st|nd|rd|th)\s*)?(?:season|stage|part|cour)\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–—:]?\s*\b(?:part|parte|cour|stage)\s*\d+\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+\bS\d{1,2}\b$"""),
    )

    private val SUBTITLE_CUT_REGEX = Regex("""(?::\s+|\s+[–—-]\s+)""")
    private val TRAILING_SEASON_DIGIT_REGEX = Regex("""\s+([2-9])$""")

    fun isSuiteTvInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SUITE_TV_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * Strips language tags `(ITA)`/`(SUB)`, trailing season/part/stage qualifiers, and arc/cour
     * subtitles (`: Nikko Kegon Falls Arc`, `: Steel Ball Run`, `: Arise from the Shadow`) so
     * TMDB/TheTVDB search in Suite TV resolves the parent series cleanly.
     */
    fun cleanTitleForSuiteTv(rawTitle: String): String {
        var cleaned = SearchUtils.stripDubTags(rawTitle)
            .replace(Regex("""\((?:SUB|SUB\s*ITA|DUB|UNCENSORED|CENSURATO|CR)\)""", RegexOption.IGNORE_CASE), "")
            .trim()

        // Strip arc/cour subtitles after ": " or " - " when the base franchise title is meaningful (>= 5 chars)
        val subMatch = SUBTITLE_CUT_REGEX.find(cleaned)
        if (subMatch != null) {
            val head = cleaned.substring(0, subMatch.range.first).trim()
            if (head.length >= 5) {
                cleaned = head
            }
        }

        for (regex in TITLE_SUFFIX_CLEAN_REGEXES) {
            val stripped = cleaned.replace(regex, "").trim()
            if (stripped.length >= 2) {
                cleaned = stripped
            }
        }

        // Strip standalone trailing season digit (e.g. "Solo Leveling 2" -> "Solo Leveling", keeping "Mob Psycho 100" & "Steins;Gate 0")
        val withoutDigit = cleaned.replace(TRAILING_SEASON_DIGIT_REGEX, "").trim()
        if (withoutDigit.length >= 4) {
            cleaned = withoutDigit
        }

        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }

    fun extractSeasonNumber(rawTitle: String): Int {
        for (regex in SEASON_EXTRACTION_REGEXES) {
            val num = regex.find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (num != null && num in 1..50) return num
        }
        return 1
    }

    fun buildDirectSeriesUrl(tvdbId: Int): String = "$SUITE_TV_SERIES_BASE_URL/$tvdbId"

    fun buildDirectEpisodeUrl(tvdbId: Int, season: Int, episode: Int): String =
        "$SUITE_TV_SERIES_BASE_URL/$tvdbId/$season/$episode"

    fun buildSearchUrl(animeTitle: String): String {
        val clean = cleanTitleForSuiteTv(animeTitle)
        if (clean.isBlank()) return SUITE_TV_HOME_URL
        val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name())
        return "$SUITE_TV_SEARCH_BASE_URL?q=$encoded"
    }

    /**
     * Resolves an anime's AniList / MAL ID into its TheTVDB series ID and canonical title via
     * public open-source ID mapping APIs (ARM v2 + ani.zip fallback), cached in memory.
     */
    suspend fun resolveSuiteTvTarget(
        httpClient: OkHttpClient,
        anime: AnimeItem,
    ): ResolvedSuiteTvTarget = withContext(Dispatchers.IO) {
        val cacheKey = "${anime.id}_${anime.anilistId}_${anime.malId}"
        resolvedTargetCache[cacheKey]?.let { return@withContext it }

        var tvdbId: Int? = null
        var tvdbSeason: Int? = null
        var tmdbId: Int? = null
        var canonicalTitle: String? = null

        // 1. ARM v2 by AniList ID
        if (anime.anilistId > 0) {
            fetchArmIds(httpClient, source = "anilist", id = anime.anilistId)?.let { res ->
                tvdbId = res.tvdbId
                tvdbSeason = res.tvdbSeason
                tmdbId = res.tmdbId
            }
        }

        // 2. ARM v2 by MyAnimeList ID (covers new cours where AniList ID isn't indexed yet, e.g. JoJo SBR 2nd Stage)
        if (tvdbId == null && anime.malId > 0) {
            fetchArmIds(httpClient, source = "myanimelist", id = anime.malId)?.let { res ->
                tvdbId = res.tvdbId
                tvdbSeason = res.tvdbSeason ?: tvdbSeason
                tmdbId = res.tmdbId ?: tmdbId
            }
        }

        // 3. Fallback: api.ani.zip mappings (also provides clean English parent title)
        if (tvdbId == null && (anime.anilistId > 0 || anime.malId > 0)) {
            val queryParam = if (anime.anilistId > 0) {
                "anilist_id=${anime.anilistId}"
            } else {
                "mal_id=${anime.malId}"
            }
            fetchAniZipTarget(httpClient, queryParam)?.let { res ->
                tvdbId = res.tvdbId ?: tvdbId
                tvdbSeason = res.tvdbSeason ?: tvdbSeason
                tmdbId = res.tmdbId ?: tmdbId
                canonicalTitle = res.canonicalTitle
            }
        }

        val resolved = ResolvedSuiteTvTarget(
            tvdbId = tvdbId,
            tvdbSeason = tvdbSeason,
            tmdbId = tmdbId,
            canonicalTitle = canonicalTitle,
        )
        if (tvdbId != null || !canonicalTitle.isNullOrBlank()) {
            resolvedTargetCache[cacheKey] = resolved
        }
        resolved
    }

    private fun fetchArmIds(httpClient: OkHttpClient, source: String, id: Int): ResolvedSuiteTvTarget? {
        val req = Request.Builder()
            .url("$ARM_API_BASE?source=$source&id=$id")
            .header("Accept", "application/json")
            .header("User-Agent", RESOLVER_UA)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val obj = prettyJson.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject
                    ?: return@use null
                val tvdb = (obj["thetvdb"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                val season = (obj["thetvdb-season"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                val tmdb = (obj["themoviedb"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                if (tvdb != null || tmdb != null) {
                    ResolvedSuiteTvTarget(tvdbId = tvdb, tvdbSeason = season, tmdbId = tmdb)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun fetchAniZipTarget(httpClient: OkHttpClient, queryParam: String): ResolvedSuiteTvTarget? {
        val req = Request.Builder()
            .url("$ANIZIP_API_BASE?$queryParam")
            .header("Accept", "application/json")
            .header("User-Agent", RESOLVER_UA)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = prettyJson.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject
                    ?: return@use null
                val mappings = root["mappings"] as? JsonObject
                val episodes = root["episodes"] as? JsonObject
                val ep1 = episodes?.get("1") as? JsonObject
                val titles = root["titles"] as? JsonObject

                val tvdb = (mappings?.get("thetvdb_id") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                    ?: (ep1?.get("tvdbShowId") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                val season = (ep1?.get("seasonNumber") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                val tmdb = (mappings?.get("themoviedb_id") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
                val enTitle = (titles?.get("en") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }
                    ?: (titles?.get("x-jat") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }

                if (tvdb != null || tmdb != null || enTitle != null) {
                    ResolvedSuiteTvTarget(
                        tvdbId = tvdb,
                        tvdbSeason = season,
                        tmdbId = tmdb,
                        canonicalTitle = enTitle,
                    )
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    /**
     * Opens the anime directly inside the native Suite TV Android app:
     * 1) If a TheTVDB series ID was resolved (`resolvedTarget.tvdbId`), opens Suite TV's verified
     *    App Link `https://www.suitetv.app/it/series/<tvdbId>` directly inside `com.nicolasbordeaux.suitetv`
     *    (landing straight on the anime's page with zero copy-paste!).
     * 2) Otherwise copies the cleaned base series title (without arc subtitles like `: Nikko Kegon Falls Arc`)
     *    to the clipboard, shows a short Toast, and launches the native Suite TV app directly.
     * 3) Falls back to Suite TV Web only when the Suite TV app is not installed.
     */
    fun openAnimeInSuiteTv(
        context: Context,
        anime: AnimeItem,
        resolvedTarget: ResolvedSuiteTvTarget? = null,
    ): Boolean {
        val cleanTitle = resolvedTarget?.canonicalTitle?.takeIf { it.isNotBlank() }
            ?: cleanTitleForSuiteTv(anime.title).ifBlank { anime.title.trim() }
        val directSeriesUrl = resolvedTarget?.tvdbId?.takeIf { it > 0 }?.let { buildDirectSeriesUrl(it) }
        val fallbackSearchUrl = buildSearchUrl(cleanTitle)
        val pm = context.packageManager

        if (isSuiteTvInstalled(context)) {
            // 1. Direct verified App Link to the exact show page (/it/series/<tvdbId>)
            if (directSeriesUrl != null) {
                val directAppIntent = Intent(Intent.ACTION_VIEW, Uri.parse(directSeriesUrl)).apply {
                    setPackage(SUITE_TV_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (directAppIntent.resolveActivity(pm) != null) {
                    if (runCatching { context.startActivity(directAppIntent) }.isSuccess) {
                        return true
                    }
                }
            }

            // 2. Fallback: copy properly cleaned base series title + launch native Suite TV app
            if (cleanTitle.isNotBlank()) {
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Suite TV", cleanTitle))
                }
                runCatching {
                    Toast.makeText(
                        context.applicationContext,
                        "Titolo copiato: $cleanTitle — cercalo in Suite TV",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            val launchIntent = pm.getLaunchIntentForPackage(SUITE_TV_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(launchIntent) }.isSuccess) {
                    return true
                }
            }
        }

        // 3. Suite TV not installed -> open direct series page or search on Suite TV Web
        val webUrl = directSeriesUrl ?: fallbackSearchUrl
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(webIntent)
            true
        }.getOrDefault(false)
    }

    fun openSuiteTvAppOrWeb(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SUITE_TV_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(launchIntent) }.isSuccess) {
                return true
            }
        }
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SUITE_TV_HOME_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(webIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * Builds a standard Trakt v2 watched-history JSON array (`watched_at`, `action`, `type`,
     * `episode { season, number }`, `show { title, year, ids { slug } }`) containing all completed
     * episodes in the user's AW Anime watch history so it can be imported directly into Suite TV.
     */
    fun buildTraktHistoryExport(history: List<WatchHistoryEntry>): SuiteTvExportResult {
        val distinctShows = LinkedHashSet<String>()
        var episodeCount = 0

        val array: JsonArray = buildJsonArray {
            for (entry in history) {
                val showTitle = cleanTitleForSuiteTv(entry.anime.title).ifBlank { entry.anime.title.trim() }
                val seasonNum = extractSeasonNumber(entry.anime.title)
                val showSlug = entry.anime.slug.takeIf { it.isNotBlank() }
                    ?: showTitle.lowercase(Locale.ROOT)
                        .replace(Regex("""[^a-z0-9]+"""), "-")
                        .trim('-')

                val completedEps = entry.episodesProgress.values
                    .filter { it.completed }
                    .sortedBy { it.updatedAt }

                for (ep in completedEps) {
                    val epNum = ep.episodeNum.trim().split(".", "-").firstOrNull()?.toIntOrNull()
                        ?: continue
                    if (epNum <= 0) continue

                    val ts = (ep.updatedAt.takeIf { it > 0L } ?: entry.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
                    val watchedAtIso = ISO_UTC_FORMATTER.format(Instant.ofEpochMilli(ts))

                    distinctShows.add(showTitle.lowercase(Locale.ROOT))
                    episodeCount++

                    add(
                        buildJsonObject {
                            put("watched_at", watchedAtIso)
                            put("action", "watch")
                            put("type", "episode")
                            putJsonObject("episode") {
                                put("season", seasonNum)
                                put("number", epNum)
                            }
                            putJsonObject("show") {
                                put("title", showTitle)
                                entry.anime.year?.let { put("year", it) }
                                putJsonObject("ids") {
                                    put("slug", showSlug)
                                }
                            }
                        }
                    )
                }
            }
        }

        val payload = prettyJson.encodeToString(JsonArray.serializer(), array)
        return SuiteTvExportResult(
            jsonPayload = payload,
            showCount = distinctShows.size,
            episodeCount = episodeCount,
        )
    }

    /**
     * Parses Suite TV / Trakt v2 / TV Time Liberator JSON watch-history exports into normalized
     * per-show/per-season watched episode sets so AW Anime can import them into local History.
     */
    fun parseImportedSuiteTvJson(rawJson: String): List<SuiteTvImportedShow> {
        val root = prettyJson.parseToJsonElement(rawJson.trim())
        val itemsArray: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                (root["shows"] as? JsonArray)
                    ?: (root["series"] as? JsonArray)
                    ?: (root["history"] as? JsonArray)
                    ?: (root["items"] as? JsonArray)
                    ?: (root["episodes"] as? JsonArray)
                    ?: JsonArray(listOf(root))
            }
            else -> return emptyList()
        }

        data class MutableShowBucket(
            val title: String,
            var year: Int? = null,
            var tvdbId: Int? = null,
            val seasonNumber: Int,
            val episodes: MutableSet<Int> = linkedSetOf(),
            var lastWatchedAtMs: Long = 0L,
        )

        val buckets = LinkedHashMap<String, MutableShowBucket>()

        fun recordEpisode(
            rawTitle: String,
            year: Int?,
            tvdbId: Int?,
            season: Int,
            epNum: Int,
            watchedAtMs: Long,
        ) {
            val clean = rawTitle.trim()
            if (clean.isBlank() || epNum <= 0) return
            val safeSeason = season.coerceAtLeast(1)
            val key = "${SearchUtils.normalizeSearchText(clean)}_s$safeSeason"
            val bucket = buckets.getOrPut(key) {
                MutableShowBucket(
                    title = clean,
                    year = year,
                    tvdbId = tvdbId,
                    seasonNumber = safeSeason,
                    lastWatchedAtMs = watchedAtMs,
                )
            }
            bucket.episodes.add(epNum)
            if (bucket.year == null && year != null) bucket.year = year
            if (bucket.tvdbId == null && tvdbId != null) bucket.tvdbId = tvdbId
            if (watchedAtMs > bucket.lastWatchedAtMs) bucket.lastWatchedAtMs = watchedAtMs
        }

        for (el in itemsArray) {
            val obj = el as? JsonObject ?: continue

            // Case 1: Trakt v2 history item: { "type": "episode", "show": {...}, "episode": {...}, "watched_at": "..." }
            val showObj = obj["show"] as? JsonObject
            val epObj = obj["episode"] as? JsonObject
            if (showObj != null && epObj != null) {
                val title = showObj.strField("title") ?: continue
                val year = showObj.intField("year")
                val tvdbId = (showObj["ids"] as? JsonObject)?.intField("tvdb")
                val season = epObj.intField("season") ?: 1
                val epNum = epObj.intField("number") ?: continue
                val ts = parseWatchedTimestampMs(obj.strField("watched_at"))
                recordEpisode(title, year, tvdbId, season, epNum, ts)
                continue
            }

            // Case 2: Suite TV / TV Time Liberator show entry: { "title"/"name", "id": { "tvdb" }, "episodes": [...], "seasons": [...] }
            val title = obj.strField("title") ?: obj.strField("name") ?: continue
            val year = obj.intField("year")
            val tvdbId = (obj["id"] as? JsonObject)?.intField("tvdb")
                ?: (obj["ids"] as? JsonObject)?.intField("tvdb")
                ?: obj.intField("tvdb_id")

            val seasonsArr = obj["seasons"] as? JsonArray
            if (seasonsArr != null) {
                for (sEl in seasonsArr) {
                    val sObj = sEl as? JsonObject ?: continue
                    val seasonNum = sObj.intField("number") ?: sObj.intField("season_number") ?: 1
                    if (seasonNum <= 0) continue // skip specials (Season 0)
                    val sEps = sObj["episodes"] as? JsonArray ?: continue
                    for (eEl in sEps) {
                        val eObj = eEl as? JsonObject ?: continue
                        val watched = eObj.boolField("is_watched") ?: eObj.boolField("watched") ?: true
                        val special = eObj.boolField("special") ?: false
                        if (!watched || special) continue
                        val epNum = eObj.intField("number") ?: eObj.intField("episode_number") ?: continue
                        val ts = parseWatchedTimestampMs(eObj.strField("watched_at") ?: eObj.strField("last_watched_at"))
                        recordEpisode(title, year, tvdbId, seasonNum, epNum, ts)
                    }
                }
            }

            val epsArr = obj["episodes"] as? JsonArray
            if (epsArr != null) {
                for (eEl in epsArr) {
                    val eObj = eEl as? JsonObject ?: continue
                    val watched = eObj.boolField("is_watched") ?: eObj.boolField("watched") ?: true
                    val special = eObj.boolField("special") ?: false
                    if (!watched || special) continue
                    val seasonNum = eObj.intField("season") ?: eObj.intField("season_number") ?: 1
                    if (seasonNum <= 0) continue
                    val epNum = eObj.intField("number") ?: eObj.intField("episode_number") ?: continue
                    val ts = parseWatchedTimestampMs(eObj.strField("watched_at") ?: eObj.strField("last_watched_at"))
                    recordEpisode(title, year, tvdbId, seasonNum, epNum, ts)
                }
            }
        }

        val now = System.currentTimeMillis()
        return buckets.values
            .filter { it.episodes.isNotEmpty() }
            .map { b ->
                SuiteTvImportedShow(
                    title = b.title,
                    year = b.year,
                    tvdbId = b.tvdbId,
                    seasonNumber = b.seasonNumber,
                    watchedEpisodes = b.episodes.toSortedSet(),
                    lastWatchedAtMs = if (b.lastWatchedAtMs > 0L) b.lastWatchedAtMs else now,
                )
            }
    }

    private fun parseWatchedTimestampMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis()
        val trimmed = raw.trim()
        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        runCatching {
            val ldt = LocalDateTime.parse(trimmed, TVTIME_DATE_FORMATTER)
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        return System.currentTimeMillis()
    }

    private fun JsonObject.strField(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.boolField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
