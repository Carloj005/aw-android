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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class SuiteTvExportResult(
    val jsonPayload: String,
    val showCount: Int,
    val episodeCount: Int,
)

data class SuiteTvZipExportResult(
    val zipBytes: ByteArray,
    val showCount: Int,
    val episodeCount: Int,
    val movieCount: Int,
    val skippedUnmappedCount: Int = 0,
)

data class ResolvedSuiteTvTarget(
    val tvdbId: Int? = null,
    val tvdbSeason: Int? = null,
    val tmdbId: Int? = null,
    val canonicalTitle: String? = null,
)

data class ArmSeasonMapping(
    val anilistId: Int? = null,
    val malId: Int? = null,
    val tvdbSeason: Int? = null,
    val tmdbId: Int? = null,
    val media: String? = null,
)

data class ArmLookupResult(
    val mappings: List<ArmSeasonMapping>,
    val confirmedNotAnime: Boolean = false,
)

data class SuiteTvImportedShow(
    val title: String,
    val year: Int? = null,
    val tvdbId: Int? = null,
    val tmdbId: Int? = null,
    val isMovie: Boolean = false,
    val isFavorite: Boolean = false,
    val isWatchlisted: Boolean = false,
    val isStopped: Boolean = false,
    val isHidden: Boolean = false,
    val seasonNumber: Int = 1,
    val watchedEpisodes: Set<Int> = emptySet(),
    val episodeTimestampsMs: Map<Int, Long> = emptyMap(),
    val lastWatchedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Integration with Suite TV (https://www.suitetv.app/it, native Android app `com.nicolasbordeaux.suitetv`).
 *
 * - Resolves AniList / MyAnimeList IDs to TheTVDB series IDs (via ARM v2 / ani.zip) to open the
 *   exact show page directly inside the native Suite TV app via verified Android App Links
 *   (`https://www.suitetv.app/it/series/<tvdbId>`).
 * - Native bidirectional Suite TV sync supporting official `suite-export-*.zip` archives
 *   (`manifest.json`, `shows.ndjson`, `movies.ndjson`), standalone `.ndjson`, and Trakt v2 `.json`.
 */
object SuiteTvIntegration {

    const val SUITE_TV_PACKAGE = "com.nicolasbordeaux.suitetv"
    const val SUITE_TV_HOME_URL = "https://www.suitetv.app/it"
    const val SUITE_TV_SEARCH_BASE_URL = "https://www.suitetv.app/it/search"
    const val SUITE_TV_SERIES_BASE_URL = "https://www.suitetv.app/it/series"

    private const val ARM_API_V2_BASE = "https://arm.haglund.dev/api/v2"
    private const val ANIZIP_API_BASE = "https://api.ani.zip/mappings"
    private const val RESOLVER_UA = "AW-Anime/1.4 (Android)"
    private const val MAX_ZIP_UNCOMPRESSED_BYTES = 20 * 1024 * 1024 // 20 MB anti-zip-bomb cap

    private val compactJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)
    private val TVTIME_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val resolvedTargetCache = ConcurrentHashMap<String, ResolvedSuiteTvTarget>()
    private val armByTvdbCache = ConcurrentHashMap<Int, List<ArmSeasonMapping>>()
    private val armByTmdbCache = ConcurrentHashMap<Int, List<ArmSeasonMapping>>()

    private val SEASON_EXTRACTION_REGEXES = listOf(
        Regex("""\b(?:season|stagione)\s*(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE),
        Regex("""\bS(\d{1,2})\b"""),
    )

    private val TITLE_SUFFIX_CLEAN_REGEXES = listOf(
        Regex("""\s*\(\d{4}\)\s*$"""),
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
     * Strips language tags `(ITA)`/`(SUB)`, trailing `(YYYY)` year suffixes, season/part/stage
     * qualifiers, and arc/cour subtitles (`: Nikko Kegon Falls Arc`, `: Steel Ball Run`) so
     * TMDB/TheTVDB and AnimeUnity matching resolve the parent series cleanly.
     */
    fun cleanTitleForSuiteTv(rawTitle: String): String {
        var cleaned = SearchUtils.stripDubTags(rawTitle.replace('×', 'x'))
            .replace(Regex("""\((?:SUB|SUB\s*ITA|DUB|UNCENSORED|CENSURATO|CR)\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
            .trim()

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
     * Resolves an anime's AniList / MAL ID into its TheTVDB series ID and season number via ARM v2
     * (+ ani.zip fallback), cached in memory.
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

        if (anime.anilistId > 0) {
            fetchArmIds(httpClient, source = "anilist", id = anime.anilistId)?.let { res ->
                tvdbId = res.tvdbId
                tvdbSeason = res.tvdbSeason
                tmdbId = res.tmdbId
            }
        }

        if (tvdbId == null && anime.malId > 0) {
            fetchArmIds(httpClient, source = "myanimelist", id = anime.malId)?.let { res ->
                tvdbId = res.tvdbId
                tvdbSeason = res.tvdbSeason ?: tvdbSeason
                tmdbId = res.tmdbId ?: tmdbId
            }
        }

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

    /**
     * Reverse-maps a TheTVDB series ID (from Suite TV `shows.ndjson`) into all matching anime
     * seasons (`anilistId`, `malId`, `tvdbSeason`), distinguishing confirmed HTTP 404 non-anime
     * (`confirmedNotAnime = true`) from transient network/429 errors (`confirmedNotAnime = false`).
     */
    suspend fun lookupArmByTvdbId(
        httpClient: OkHttpClient,
        tvdbId: Int,
    ): ArmLookupResult = withContext(Dispatchers.IO) {
        if (tvdbId <= 0) return@withContext ArmLookupResult(emptyList(), confirmedNotAnime = false)
        armByTvdbCache[tvdbId]?.let { cached ->
            return@withContext ArmLookupResult(cached, confirmedNotAnime = cached.isEmpty())
        }
        val parsed = fetchArmReverseListWithRetry(httpClient, "$ARM_API_V2_BASE/thetvdb?id=$tvdbId")
        if (parsed != null) {
            armByTvdbCache[tvdbId] = parsed
            ArmLookupResult(parsed, confirmedNotAnime = parsed.isEmpty())
        } else {
            ArmLookupResult(emptyList(), confirmedNotAnime = false)
        }
    }

    suspend fun resolveArmByTvdbId(
        httpClient: OkHttpClient,
        tvdbId: Int,
    ): List<ArmSeasonMapping> = lookupArmByTvdbId(httpClient, tvdbId).mappings

    /**
     * Reverse-maps a TMDB ID (from Suite TV `movies.ndjson`) into matching anime movie IDs.
     */
    suspend fun lookupArmByTmdbId(
        httpClient: OkHttpClient,
        tmdbId: Int,
    ): ArmLookupResult = withContext(Dispatchers.IO) {
        if (tmdbId <= 0) return@withContext ArmLookupResult(emptyList(), confirmedNotAnime = false)
        armByTmdbCache[tmdbId]?.let { cached ->
            return@withContext ArmLookupResult(cached, confirmedNotAnime = cached.isEmpty())
        }
        val parsed = fetchArmReverseListWithRetry(httpClient, "$ARM_API_V2_BASE/themoviedb?id=$tmdbId")
        if (parsed != null) {
            armByTmdbCache[tmdbId] = parsed
            ArmLookupResult(parsed, confirmedNotAnime = parsed.isEmpty())
        } else {
            ArmLookupResult(emptyList(), confirmedNotAnime = false)
        }
    }

    suspend fun resolveArmByTmdbId(
        httpClient: OkHttpClient,
        tmdbId: Int,
    ): List<ArmSeasonMapping> = lookupArmByTmdbId(httpClient, tmdbId).mappings

    private suspend fun fetchArmReverseListWithRetry(
        httpClient: OkHttpClient,
        url: String,
    ): List<ArmSeasonMapping>? {
        val first = fetchArmReverseList(httpClient, url)
        if (first != null) return first
        delay(600L)
        return fetchArmReverseList(httpClient, url)
    }

    private fun fetchArmReverseList(httpClient: OkHttpClient, url: String): List<ArmSeasonMapping>? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", RESOLVER_UA)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@use emptyList()
                if (!resp.isSuccessful) return@use null
                val arr = compactJson.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonArray
                    ?: return@use emptyList()
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val anilist = obj.intField("anilist")?.takeIf { it > 0 }
                    val mal = obj.intField("myanimelist")?.takeIf { it > 0 }
                    if (anilist == null && mal == null) return@mapNotNull null
                    ArmSeasonMapping(
                        anilistId = anilist,
                        malId = mal,
                        tvdbSeason = obj.intField("thetvdb-season"),
                        tmdbId = obj.intField("themoviedb")?.takeIf { it > 0 },
                        media = obj.strField("media"),
                    )
                }
            }
        }.getOrNull()
    }

    private fun fetchArmIds(httpClient: OkHttpClient, source: String, id: Int): ResolvedSuiteTvTarget? {
        val req = Request.Builder()
            .url("$ARM_API_V2_BASE/ids?source=$source&id=$id")
            .header("Accept", "application/json")
            .header("User-Agent", RESOLVER_UA)
            .build()
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val obj = compactJson.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject
                    ?: return@use null
                val tvdb = obj.intField("thetvdb")?.takeIf { it > 0 }
                val season = obj.intField("thetvdb-season")?.takeIf { it > 0 }
                val tmdb = obj.intField("themoviedb")?.takeIf { it > 0 }
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
                val root = compactJson.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject
                    ?: return@use null
                val mappings = root["mappings"] as? JsonObject
                val episodes = root["episodes"] as? JsonObject
                val ep1 = episodes?.get("1") as? JsonObject
                val titles = root["titles"] as? JsonObject

                val tvdb = mappings?.intField("thetvdb_id")?.takeIf { it > 0 }
                    ?: ep1?.intField("tvdbShowId")?.takeIf { it > 0 }
                val season = ep1?.intField("seasonNumber")?.takeIf { it > 0 }
                val tmdb = mappings?.intField("themoviedb_id")?.takeIf { it > 0 }
                val enTitle = titles?.strField("en") ?: titles?.strField("x-jat")

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
     *    App Link `https://www.suitetv.app/it/series/<tvdbId>` directly inside `com.nicolasbordeaux.suitetv`.
     * 2) Otherwise copies the cleaned base series title + shows Toast + launches native Suite TV app.
     * 3) Falls back to Suite TV Web only if Suite TV app is not installed.
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
     * Builds a native Suite TV `suite-export` v1 `.zip` archive (`manifest.json`, `shows.ndjson`,
     * `movies.ndjson`, `lists.ndjson`, `comments.ndjson`) matching Suite TV's exact official schema.
     */
    fun buildNativeSuiteTvZip(
        history: List<WatchHistoryEntry>,
        favorites: List<AnimeItem>,
        resolvedTargetsByKey: Map<String, ResolvedSuiteTvTarget>,
        appVersion: String = "1.4.2",
        appBuild: Int = 12,
    ): SuiteTvZipExportResult {
        val favoriteKeys = favorites.map { it.uniqueKey }.toSet()

        data class ShowExportBucket(
            val tvdbId: Int,
            val tmdbId: Int?,
            val title: String,
            var isFavorite: Boolean,
            val watchedBySeasonEp: LinkedHashMap<Pair<Int, Int>, Long> = linkedMapOf(),
        )

        data class MovieExportBucket(
            val tvdbId: Int,
            val tmdbId: Int?,
            val title: String,
            val year: Int?,
            var isFavorite: Boolean,
            var watchedAt: Long?,
        )

        val showsByTvdb = LinkedHashMap<Int, ShowExportBucket>()
        val moviesByTvdb = LinkedHashMap<Int, MovieExportBucket>()
        var skippedUnmapped = 0

        for (entry in history) {
            val target = resolvedTargetsByKey[entry.uniqueKey]
            val tvdbId = target?.tvdbId?.takeIf { it > 0 }
            if (tvdbId == null) {
                skippedUnmapped++
                continue
            }
            val cleanTitle = target.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: cleanTitleForSuiteTv(entry.anime.title).ifBlank { entry.anime.title.trim() }
            val isFav = entry.uniqueKey in favoriteKeys

            if (entry.anime.type.equals("Movie", ignoreCase = true)) {
                val completedEp = entry.episodesProgress.values.firstOrNull { it.completed }
                val watchedAt = completedEp?.updatedAt?.takeIf { it > 0L }
                    ?: if (entry.completed) entry.updatedAt.takeIf { it > 0L } else null
                val prev = moviesByTvdb[tvdbId]
                if (prev == null) {
                    moviesByTvdb[tvdbId] = MovieExportBucket(
                        tvdbId = tvdbId,
                        tmdbId = target.tmdbId,
                        title = cleanTitle,
                        year = entry.anime.year,
                        isFavorite = isFav,
                        watchedAt = watchedAt,
                    )
                } else {
                    prev.isFavorite = prev.isFavorite || isFav
                    if (watchedAt != null && (prev.watchedAt == null || watchedAt > prev.watchedAt!!)) {
                        prev.watchedAt = watchedAt
                    }
                }
                continue
            }

            val seasonNum = (target.tvdbSeason ?: extractSeasonNumber(entry.anime.title)).coerceAtLeast(1)
            val bucket = showsByTvdb.getOrPut(tvdbId) {
                ShowExportBucket(
                    tvdbId = tvdbId,
                    tmdbId = target.tmdbId,
                    title = cleanTitle,
                    isFavorite = isFav,
                )
            }
            bucket.isFavorite = bucket.isFavorite || isFav

            for (ep in entry.episodesProgress.values.filter { it.completed }) {
                val epNum = ep.episodeNum.trim().split(".", "-").firstOrNull()?.toIntOrNull() ?: continue
                if (epNum <= 0) continue
                val ts = ep.updatedAt.takeIf { it > 0L }
                    ?: entry.updatedAt.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                val key = seasonNum to epNum
                val prevTs = bucket.watchedBySeasonEp[key]
                if (prevTs == null || ts > prevTs) {
                    bucket.watchedBySeasonEp[key] = ts
                }
            }
        }

        // Also include favorited anime not yet in history (marked as favorite/watchlisted)
        for (fav in favorites) {
            val target = resolvedTargetsByKey[fav.uniqueKey]
            val tvdbId = target?.tvdbId?.takeIf { it > 0 } ?: continue
            val cleanTitle = target.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: cleanTitleForSuiteTv(fav.title).ifBlank { fav.title.trim() }
            if (fav.type.equals("Movie", ignoreCase = true)) {
                moviesByTvdb.putIfAbsent(
                    tvdbId,
                    MovieExportBucket(
                        tvdbId = tvdbId,
                        tmdbId = target.tmdbId,
                        title = cleanTitle,
                        year = fav.year,
                        isFavorite = true,
                        watchedAt = null,
                    )
                )
            } else {
                val bucket = showsByTvdb.getOrPut(tvdbId) {
                    ShowExportBucket(
                        tvdbId = tvdbId,
                        tmdbId = target.tmdbId,
                        title = cleanTitle,
                        isFavorite = true,
                    )
                }
                bucket.isFavorite = true
            }
        }

        var totalEpisodes = 0
        val showsNdjson = buildString {
            for (show in showsByTvdb.values) {
                totalEpisodes += show.watchedBySeasonEp.size
                val sortedWatched = show.watchedBySeasonEp.entries
                    .sortedWith(compareBy({ it.key.first }, { it.key.second }))
                val lineObj = buildJsonObject {
                    put("id", "show:tvdb:${show.tvdbId}")
                    put("tvdbId", show.tvdbId)
                    if (show.tmdbId != null) put("realTmdbId", show.tmdbId) else put("realTmdbId", JsonNull)
                    put("title", show.title)
                    put("isFavorite", show.isFavorite)
                    put("isWatchlisted", show.watchedBySeasonEp.isEmpty())
                    put("isStopped", false)
                    put("isHidden", false)
                    putJsonArray("watched") {
                        for ((seasonEp, ts) in sortedWatched) {
                            add(
                                buildJsonObject {
                                    put("season", seasonEp.first)
                                    put("number", seasonEp.second)
                                    put("watchedAt", ts)
                                    put("rating", JsonNull)
                                    put("rewatchCount", 0)
                                    putJsonArray("rewatchDates") {}
                                    put("name", JsonNull)
                                    put("tvdbEpisodeId", JsonNull)
                                    putJsonArray("emotions") {}
                                    put("favoriteCharacterId", JsonNull)
                                    put("favoriteCharacterName", JsonNull)
                                    put("favoriteCharacterActor", JsonNull)
                                    put("favoriteCharacterTvdbCharacterId", JsonNull)
                                }
                            )
                        }
                    }
                }
                appendLine(compactJson.encodeToString(JsonObject.serializer(), lineObj))
            }
        }

        val moviesNdjson = buildString {
            for (movie in moviesByTvdb.values) {
                val lineObj = buildJsonObject {
                    put("id", "movie:tvdb:${movie.tvdbId}")
                    put("tvdbId", movie.tvdbId)
                    put("imdbId", JsonNull)
                    if (movie.tmdbId != null) put("realTmdbId", movie.tmdbId) else put("realTmdbId", JsonNull)
                    put("title", movie.title)
                    put("isFavorite", movie.isFavorite)
                    put("isHidden", false)
                    if (movie.watchedAt != null) put("watchedAt", movie.watchedAt) else put("watchedAt", JsonNull)
                    put("rating", JsonNull)
                    if (movie.year != null) put("year", movie.year) else put("year", JsonNull)
                    put("rewatchCount", 0)
                    putJsonArray("rewatchDates") {}
                    putJsonArray("emotions") {}
                    put("favoriteCharacterId", JsonNull)
                    put("favoriteCharacterName", JsonNull)
                    put("favoriteCharacterActor", JsonNull)
                }
                appendLine(compactJson.encodeToString(JsonObject.serializer(), lineObj))
            }
        }

        val manifestObj = buildJsonObject {
            put("format", "suite-export")
            put("version", 1)
            put("createdAt", System.currentTimeMillis())
            put("platform", "android")
            put("appVersion", appVersion)
            put("appBuild", appBuild.toString())
            putJsonArray("scope") {
                add(JsonPrimitive("import"))
                add(JsonPrimitive("library"))
            }
            putJsonObject("counts") {
                put("shows", showsByTvdb.size)
                put("episodes", totalEpisodes)
                put("movies", moviesByTvdb.size)
                put("lists", 0)
                put("comments", 0)
                put("media", 0)
            }
        }
        val manifestJson = prettyJson.encodeToString(JsonObject.serializer(), manifestObj)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun writeEntry(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            writeEntry("manifest.json", manifestJson)
            writeEntry("shows.ndjson", showsNdjson)
            writeEntry("movies.ndjson", moviesNdjson)
            writeEntry("lists.ndjson", "")
            writeEntry("comments.ndjson", "")
        }

        return SuiteTvZipExportResult(
            zipBytes = baos.toByteArray(),
            showCount = showsByTvdb.size,
            episodeCount = totalEpisodes,
            movieCount = moviesByTvdb.size,
            skippedUnmappedCount = skippedUnmapped,
        )
    }

    /**
     * Builds a standard Trakt v2 watched-history JSON array (`watched_at`, `action`, `type`,
     * `episode { season, number }`, `show { title, year, ids { slug } }`) containing all completed
     * episodes in the user's AW Anime watch history.
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
     * Parses ANY supported Suite TV export byte payload:
     * 1) Native Suite TV `.zip` archive (`suite-export-*.zip` containing `shows.ndjson` & `movies.ndjson`)
     * 2) Standalone Suite TV `.ndjson` (`shows.ndjson` or `movies.ndjson`)
     * 3) Trakt v2 `.json` or TV Time Liberator `.json`
     */
    fun parseImportedSuiteTvBytes(rawBytes: ByteArray): List<SuiteTvImportedShow> {
        if (rawBytes.isEmpty()) return emptyList()

        // ZIP magic header: PK\x03\x04
        if (rawBytes.size >= 4 &&
            rawBytes[0] == 0x50.toByte() &&
            rawBytes[1] == 0x4B.toByte() &&
            rawBytes[2] == 0x03.toByte() &&
            rawBytes[3] == 0x04.toByte()
        ) {
            return parseSuiteTvExportZip(rawBytes)
        }

        val text = rawBytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return emptyList()

        // Detect NDJSON (multiple lines where first non-empty line is a JSON object ending with '}')
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val looksLikeSuiteNdjson = firstLine.startsWith("{") &&
            firstLine.endsWith("}") &&
            (firstLine.contains("\"show:tvdb:") || firstLine.contains("\"movie:tvdb:") || firstLine.contains("\"tvdbId\""))
        if (looksLikeSuiteNdjson) {
            val shows = parseSuiteTvShowsNdjson(text)
            val movies = parseSuiteTvMoviesNdjson(text)
            return shows + movies
        }

        return parseImportedSuiteTvJson(text)
    }

    private fun parseSuiteTvExportZip(zipBytes: ByteArray): List<SuiteTvImportedShow> {
        var showsText: String? = null
        var moviesText: String? = null
        var fallbackJsonText: String? = null
        var totalUncompressed = 0

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                if (!entry.isDirectory && (name == "shows.ndjson" || name == "movies.ndjson" || name.endsWith(".json"))) {
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val read = zis.read(buf)
                        if (read == -1) break
                        totalUncompressed += read
                        if (totalUncompressed > MAX_ZIP_UNCOMPRESSED_BYTES) {
                            throw IOException("Archivio ZIP troppo grande (limite 20 MB)")
                        }
                        out.write(buf, 0, read)
                    }
                    val content = out.toString(Charsets.UTF_8.name())
                    when (name) {
                        "shows.ndjson" -> showsText = content
                        "movies.ndjson" -> moviesText = content
                        "manifest.json", "profile.json" -> Unit
                        else -> if (fallbackJsonText == null) fallbackJsonText = content
                    }
                }
                zis.closeEntry()
            }
        }

        val results = mutableListOf<SuiteTvImportedShow>()
        showsText?.let { results.addAll(parseSuiteTvShowsNdjson(it)) }
        moviesText?.let { results.addAll(parseSuiteTvMoviesNdjson(it)) }
        val fallback = fallbackJsonText
        if (results.isEmpty() && !fallback.isNullOrBlank()) {
            results.addAll(parseImportedSuiteTvJson(fallback))
        }
        return results
    }

    fun parseSuiteTvShowsNdjson(ndjson: String): List<SuiteTvImportedShow> {
        val result = mutableListOf<SuiteTvImportedShow>()
        val now = System.currentTimeMillis()

        for (rawLine in ndjson.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.startsWith("{")) continue
            val obj = runCatching { compactJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            val idStr = obj.strField("id").orEmpty()
            if (idStr.startsWith("movie:")) continue

            val title = obj.strField("title") ?: continue
            val tvdbId = obj.intField("tvdbId")?.takeIf { it > 0 }
                ?: idStr.substringAfter("show:tvdb:", "").toIntOrNull()?.takeIf { it > 0 }
            val tmdbId = obj.intField("realTmdbId")?.takeIf { it > 0 }
            val isFavorite = obj.boolField("isFavorite") ?: false
            val isWatchlisted = obj.boolField("isWatchlisted") ?: false
            val isStopped = obj.boolField("isStopped") ?: false
            val isHidden = obj.boolField("isHidden") ?: false

            val watchedArr = obj["watched"] as? JsonArray
            val bySeason = LinkedHashMap<Int, LinkedHashMap<Int, Long>>()

            if (watchedArr != null) {
                for (wEl in watchedArr) {
                    val wObj = wEl as? JsonObject ?: continue
                    val season = wObj.intField("season") ?: 1
                    if (season <= 0) continue // skip Season 0 specials
                    val epNum = wObj.intField("number") ?: continue
                    if (epNum <= 0) continue
                    val watchedAtMs = wObj.longField("watchedAt")?.takeIf { it > 0L } ?: now
                    val seasonMap = bySeason.getOrPut(season) { linkedMapOf() }
                    val prev = seasonMap[epNum]
                    if (prev == null || watchedAtMs > prev) {
                        seasonMap[epNum] = watchedAtMs
                    }
                }
            }

            if (bySeason.isNotEmpty()) {
                for ((season, epMap) in bySeason) {
                    val maxTs = epMap.values.maxOrNull() ?: now
                    result.add(
                        SuiteTvImportedShow(
                            title = title,
                            tvdbId = tvdbId,
                            tmdbId = tmdbId,
                            isMovie = false,
                            isFavorite = isFavorite,
                            isWatchlisted = isWatchlisted,
                            isStopped = isStopped,
                            isHidden = isHidden,
                            seasonNumber = season,
                            watchedEpisodes = epMap.keys.toSortedSet(),
                            episodeTimestampsMs = epMap,
                            lastWatchedAtMs = maxTs,
                        )
                    )
                }
            } else if ((isFavorite || isWatchlisted) && !isHidden && !isStopped) {
                result.add(
                    SuiteTvImportedShow(
                        title = title,
                        tvdbId = tvdbId,
                        tmdbId = tmdbId,
                        isMovie = false,
                        isFavorite = isFavorite,
                        isWatchlisted = isWatchlisted,
                        isStopped = false,
                        isHidden = false,
                        seasonNumber = 1,
                        watchedEpisodes = emptySet(),
                        lastWatchedAtMs = now,
                    )
                )
            }
        }
        return result
    }

    fun parseSuiteTvMoviesNdjson(ndjson: String): List<SuiteTvImportedShow> {
        val result = mutableListOf<SuiteTvImportedShow>()
        for (rawLine in ndjson.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.startsWith("{")) continue
            val obj = runCatching { compactJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            val idStr = obj.strField("id").orEmpty()
            if (idStr.startsWith("show:")) continue
            if (!idStr.startsWith("movie:") && obj["watched"] is JsonArray) continue

            val title = obj.strField("title") ?: continue
            val watchedAt = obj.longField("watchedAt")?.takeIf { it > 0L }
            val isFavorite = obj.boolField("isFavorite") ?: false
            val isHidden = obj.boolField("isHidden") ?: false
            if (watchedAt == null && !isFavorite) continue
            if (isHidden) continue

            val tvdbId = obj.intField("tvdbId")?.takeIf { it > 0 }
                ?: idStr.substringAfter("movie:tvdb:", "").toIntOrNull()?.takeIf { it > 0 }
            val tmdbId = obj.intField("realTmdbId")?.takeIf { it > 0 }
            val year = obj.intField("year")

            result.add(
                SuiteTvImportedShow(
                    title = title,
                    year = year,
                    tvdbId = tvdbId,
                    tmdbId = tmdbId,
                    isMovie = true,
                    isFavorite = isFavorite,
                    isWatchlisted = watchedAt == null,
                    seasonNumber = 1,
                    watchedEpisodes = if (watchedAt != null) setOf(1) else emptySet(),
                    episodeTimestampsMs = if (watchedAt != null) mapOf(1 to watchedAt) else emptyMap(),
                    lastWatchedAtMs = watchedAt ?: System.currentTimeMillis(),
                )
            )
        }
        return result
    }

    /**
     * Parses Trakt v2 / TV Time Liberator JSON watch-history exports into normalized
     * per-show/per-season watched episode sets.
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
            val episodes: LinkedHashMap<Int, Long> = linkedMapOf(),
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
            val prev = bucket.episodes[epNum]
            if (prev == null || watchedAtMs > prev) bucket.episodes[epNum] = watchedAtMs
            if (bucket.year == null && year != null) bucket.year = year
            if (bucket.tvdbId == null && tvdbId != null) bucket.tvdbId = tvdbId
            if (watchedAtMs > bucket.lastWatchedAtMs) bucket.lastWatchedAtMs = watchedAtMs
        }

        for (el in itemsArray) {
            val obj = el as? JsonObject ?: continue

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
                    if (seasonNum <= 0) continue
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
                    watchedEpisodes = b.episodes.keys.toSortedSet(),
                    episodeTimestampsMs = b.episodes,
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

    private fun JsonObject.longField(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.boolField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull
}
