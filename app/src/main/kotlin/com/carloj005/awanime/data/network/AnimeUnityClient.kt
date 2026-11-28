package com.carloj005.awanime.data.network

import android.content.Context
import com.carloj005.awanime.data.model.AnimeDetailWithRelated
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.CalendarDayGroup
import com.carloj005.awanime.data.model.DirectDownloadSource
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.GenreItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.SearchTypeFilter
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.StreamResolution
import com.carloj005.awanime.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Native Kotlin client for AnimeUnity (Laravel + Inertia.js + REST API).
 * JSON mapping is delegated to [AnimeUnityParser], stream extraction to [VixCloudExtractor],
 * and skip-time consensus to [SkipTimeRemoteDataSource] (Single Responsibility Principle).
 */
class AnimeUnityClient(
    context: Context? = null,
    val httpClients: HttpClients = HttpClients(),
    private val networkMonitor: NetworkMonitor = NetworkMonitor(context),
    val vixCloudExtractor: VixCloudExtractor = VixCloudExtractor(httpClients.externalClient),
    val skipTimeDataSource: SkipTimeRemoteDataSource = SkipTimeRemoteDataSource(httpClients.fastExternalClient),
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://animeunity.cx"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val CANDIDATE_TLDS = listOf(
            "cx", "co", "so", "to", "tv", "it", "cc", "io", "me", "ac", "org", "net",
        )

        private const val PREFS_NAME = "aw_anime_prefs"
        private const val KEY_BASE_URL = "animeunity_base_url"
        private val INERTIA_PAGE_REGEX = Regex("""data-page="([^"]+)"""")

        fun mapStatus(raw: String?): String = AnimeUnityParser.mapStatus(raw)

        fun isValidAnimeUnityHtml(html: String): Boolean {
            if (html.isBlank()) return false
            val lower = html.lowercase()
            return "data-page=" in html && (
                "latestepisodes" in lower ||
                    "carouselanimes" in lower ||
                    "scws_url" in lower ||
                    "api.anime.episodes.index" in lower ||
                    ("ziggy" in lower && "animeunity" in lower)
                )
        }

        fun decodeHtmlEntities(raw: String): String {
            return raw
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
        }

        fun isTrustedAnimeUnityOrigin(rawUrl: String): Boolean =
            UrlSecurityPolicy.isTrustedAnimeUnityOrigin(rawUrl)

        fun isTrustedEmbedUrl(rawUrl: String): Boolean =
            UrlSecurityPolicy.isTrustedEmbedUrl(rawUrl)
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sessionMutex = Mutex()

    @Volatile
    var baseUrl: String = prefs?.getString(KEY_BASE_URL, DEFAULT_BASE_URL)
        ?.takeIf { isTrustedAnimeUnityOrigin(it) }
        ?: DEFAULT_BASE_URL
        private set

    @Volatile
    private var inertiaVersion: String = ""

    @Volatile
    private var sessionReady: Boolean = false

    @Volatile
    private var cachedHeroCarousel: List<AnimeItem> = emptyList()

    @Volatile
    private var cachedGenres: List<GenreItem> = emptyList()

    private fun extractInertiaPage(html: String): JsonObject? {
        val match = INERTIA_PAGE_REGEX.find(html) ?: return null
        val decoded = decodeHtmlEntities(match.groupValues[1])
        return runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
    }

    private fun requireOnline() {
        if (!networkMonitor.isOnline()) {
            throw OfflineException("Nessuna connessione internet attiva.")
        }
    }

    suspend fun ensureSession(forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        requireOnline()
        if (sessionReady && inertiaVersion.isNotBlank() && !forceRefresh) return@withContext
        sessionMutex.withLock {
            requireOnline()
            if (sessionReady && inertiaVersion.isNotBlank() && !forceRefresh) return@withLock

            val candidates = linkedSetOf(baseUrl, DEFAULT_BASE_URL, "https://www.animeunity.cx")
            for (tld in CANDIDATE_TLDS) {
                candidates.add("https://animeunity.$tld")
                candidates.add("https://www.animeunity.$tld")
            }

            for (cand in candidates) {
                if (!networkMonitor.isOnline()) {
                    throw OfflineException("Connessione internet assente.")
                }
                try {
                    val req = Request.Builder()
                        .url("$cand/")
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                        .build()
                    httpClients.sessionClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful && isValidAnimeUnityHtml(body)) {
                            val finalOrigin = "${resp.request.url.scheme}://${resp.request.url.host}"
                            if (!isTrustedAnimeUnityOrigin(finalOrigin)) continue
                            baseUrl = finalOrigin
                            prefs?.edit()?.putString(KEY_BASE_URL, finalOrigin)?.apply()
                            val page = extractInertiaPage(body)
                            inertiaVersion = page?.str("version").orEmpty()
                            val carouselArr = page?.get("props")?.jsonObject?.get("carouselAnimes") as? JsonArray
                            if (carouselArr != null) {
                                cachedHeroCarousel = carouselArr.mapNotNull { el ->
                                    val obj = el as? JsonObject ?: return@mapNotNull null
                                    AnimeUnityParser.parseAnimeVariants(obj).firstOrNull()
                                }
                            }
                            sessionReady = true
                            return@withLock
                        }
                    }
                } catch (_: IOException) {
                    continue
                }
            }
            throw IOException("Impossibile raggiungere AnimeUnity ($baseUrl). Controlla la connessione internet.")
        }
    }

    private suspend fun inertiaGet(
        path: String,
        component: String,
        partialData: List<String> = emptyList(),
    ): JsonObject = withContext(Dispatchers.IO) {
        ensureSession()
        for (attempt in 0..1) {
            val reqBuilder = Request.Builder()
                .url("$baseUrl$path")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$baseUrl/")
                .header("Accept", "application/json, text/html;q=0.9")
            if (inertiaVersion.isNotBlank()) {
                reqBuilder
                    .header("X-Inertia", "true")
                    .header("X-Inertia-Version", inertiaVersion)
                    .header("X-Requested-With", "XMLHttpRequest")
                if (partialData.isNotEmpty()) {
                    reqBuilder
                        .header("X-Inertia-Partial-Component", component)
                        .header("X-Inertia-Partial-Data", partialData.joinToString(","))
                }
            }
            httpClients.sessionClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 409 && attempt == 0) {
                    ensureSession(forceRefresh = true)
                    return@use
                }
                if (!resp.isSuccessful) {
                    throw IOException("Errore HTTP ${resp.code} su $path")
                }
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                val text = resp.body?.string().orEmpty()
                val page = if ("json" in ct || text.trimStart().startsWith("{")) {
                    json.parseToJsonElement(text).jsonObject
                } else {
                    extractInertiaPage(text)
                        ?: throw IOException("Risposta Inertia non valida per $path")
                }
                page.str("version")?.takeIf { it.isNotBlank() }?.let { inertiaVersion = it }
                return@withContext page
            }
        }
        throw IOException("Errore versione Inertia (409) persistente su $path")
    }

    private fun apiGetJson(pathWithQuery: String): JsonElement {
        val req = Request.Builder()
            .url("$baseUrl$pathWithQuery")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "$baseUrl/")
            .build()
        httpClients.sessionClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Errore HTTP ${resp.code} su $pathWithQuery")
            }
            val text = resp.body?.string().orEmpty()
            return json.parseToJsonElement(text)
        }
    }

    suspend fun getHomeCarousel(forceRefresh: Boolean = false): List<AnimeItem> = withContext(Dispatchers.IO) {
        ensureSession(forceRefresh = forceRefresh)
        cachedHeroCarousel
    }

    suspend fun getWeeklyCalendar(): List<CalendarDayGroup> = withContext(Dispatchers.IO) {
        ensureSession()
        val payload = apiGetJson("/api/calendar").jsonObject
        AnimeUnityParser.parseCalendarPayload(payload)
    }

    suspend fun getLatestEpisodes(filter: LatestFilter): List<AnimeItem> = withContext(Dispatchers.IO) {
        ensureSession()
        if (filter == LatestFilter.TRENDING) {
            return@withContext fetchTrendingAnimes()
        }

        val langQuery = when (filter) {
            LatestFilter.SUB -> "&language=sub"
            LatestFilter.DUB -> "&language=dub"
            else -> ""
        }
        val collected = mutableListOf<AnimeItem>()
        val seenLatestKeys = HashSet<String>()
        for (page in 1..2) {
            val payload = apiGetJson("/api/home/latest-episodes?page=$page$langQuery").jsonObject
            val arr = payload["data"]?.jsonArray ?: break
            if (arr.isEmpty()) break
            for (elem in arr) {
                val parsed = AnimeUnityParser.parseLatestEpisodeItem(elem as? JsonObject) ?: continue
                val dedupeKey = "${parsed.uniqueKey}_${parsed.latestVideoId ?: parsed.latestEpisodeNum.orEmpty()}"
                if (seenLatestKeys.add(dedupeKey)) {
                    collected.add(parsed)
                }
            }
            val lastPage = payload.intVal("last_page") ?: 1
            if (page >= lastPage) break
        }
        collected
    }

    private fun fetchTrendingAnimes(): List<AnimeItem> {
        val payload = apiGetJson("/api/top-anime?page=1").jsonObject
        val arr = payload["data"]?.jsonArray ?: JsonArray(emptyList())
        val seenTrending = HashSet<String>()
        return arr.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val primary = AnimeUnityParser.parseAnimeVariants(obj).firstOrNull() ?: return@mapNotNull null
            if (!seenTrending.add(primary.uniqueKey)) return@mapNotNull null
            primary.copy(
                latestEpisodeNum = if (primary.releasedEpisodes > 0) primary.releasedEpisodes.toString() else "1",
            )
        }
    }

    private fun querySearchRecords(query: String): List<AnimeItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val mergedById = LinkedHashMap<String, JsonObject>()

        try {
            for (page in 1..3) {
                val payload = apiGetJson("/api/archive?query=$encoded&page=$page").jsonObject
                val arr = payload["data"]?.jsonArray ?: break
                for (el in arr) {
                    val obj = el as? JsonObject ?: continue
                    val id = obj.str("id") ?: continue
                    mergedById.putIfAbsent(id, obj)
                }
                val lastPage = payload.intVal("last_page") ?: 1
                val perPage = payload.intVal("per_page") ?: 30
                if (arr.size < perPage || page >= lastPage) break
            }
        } catch (_: IOException) {
            // Fallback to /api/search below
        }

        try {
            val payload = apiGetJson("/api/search?q=$encoded").jsonObject
            val arr = payload["data"]?.jsonArray
            if (arr != null) {
                for (el in arr) {
                    val obj = el as? JsonObject ?: continue
                    val id = obj.str("id") ?: continue
                    mergedById.putIfAbsent(id, obj)
                }
            }
        } catch (e: IOException) {
            if (mergedById.isEmpty()) throw e
        }

        return mergedById.values.flatMap { AnimeUnityParser.parseAnimeVariants(it) }
    }

    suspend fun search(rawQuery: String): List<AnimeItem> = withContext(Dispatchers.IO) {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        ensureSession()

        val expanded = SearchUtils.expandQueryAlias(trimmed)
        val primary = querySearchRecords(expanded)
        if (primary.isNotEmpty()) {
            return@withContext SearchUtils.sortAnimeItems(primary, expanded)
        }

        val fallbackByKey = LinkedHashMap<String, AnimeItem>()
        val queriesToTry = buildList {
            if (!expanded.equals(trimmed, ignoreCase = true)) add(trimmed)
            addAll(SearchUtils.generateFallbackQueries(expanded))
        }
        for (subQuery in queriesToTry) {
            val subHits = runCatchingCancellable { querySearchRecords(subQuery) }.getOrDefault(emptyList())
            for (item in subHits) {
                fallbackByKey.putIfAbsent(item.uniqueKey, item)
            }
        }
        if (fallbackByKey.isEmpty()) return@withContext emptyList()

        val chronologicallyOrdered = SearchUtils.sortAnimeItems(fallbackByKey.values.toList(), expanded)
        SearchUtils.rankFuzzyCandidates(expanded, chronologicallyOrdered)
    }

    /**
     * Fetches and caches the official AnimeUnity genre catalog from `/archive` Inertia props.
     */
    suspend fun getGenres(): List<GenreItem> = withContext(Dispatchers.IO) {
        if (cachedGenres.isNotEmpty()) return@withContext cachedGenres
        val page = inertiaGet("/archive", component = "Archive", partialData = listOf("animeGenres"))
        val arr = page["props"]?.jsonObject?.get("animeGenres") as? JsonArray ?: return@withContext emptyList()
        val parsed = arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.intVal("id") ?: return@mapNotNull null
            val name = obj.str("name")?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (name.equals("Hentai", ignoreCase = true)) return@mapNotNull null
            GenreItem(id = id, name = name)
        }
        if (parsed.isNotEmpty()) cachedGenres = parsed
        parsed
    }

    /**
     * Server-side filtered archive discovery (works even with an empty query: genre, type, language,
     * status, year, and score sorting).
     */
    suspend fun browseArchive(
        query: String = "",
        genreId: Int? = null,
        typeFilter: SearchTypeFilter = SearchTypeFilter.ALL,
        language: String? = null,
        status: String? = null,
        sortByScore: Boolean = false,
    ): List<AnimeItem> = withContext(Dispatchers.IO) {
        ensureSession()
        val params = buildList {
            val q = SearchUtils.expandQueryAlias(query).trim()
            if (q.isNotEmpty()) add("query=${URLEncoder.encode(q, StandardCharsets.UTF_8.name())}")
            if (genreId != null) add("genres[]=$genreId")
            typeFilter.apiValue?.let { add("type=$it") }
            language?.let { add("language=$it") }
            status?.let { add("status=$it") }
            if (sortByScore) add("sort_by=score")
        }
        val queryString = if (params.isNotEmpty()) params.joinToString("&") + "&" else ""
        val mergedById = LinkedHashMap<String, JsonObject>()
        for (page in 1..2) {
            val payload = apiGetJson("/api/archive?${queryString}page=$page").jsonObject
            val arr = payload["data"]?.jsonArray ?: break
            for (el in arr) {
                val obj = el as? JsonObject ?: continue
                val id = obj.str("id") ?: continue
                mergedById.putIfAbsent(id, obj)
            }
            val lastPage = payload.intVal("last_page") ?: 1
            if (page >= lastPage) break
        }
        mergedById.values.flatMap { AnimeUnityParser.parseAnimeVariants(it) }
    }

    /**
     * Picks a genuine random anime from AnimeUnity's `/random` endpoint (skipping already-completed
     * anime where possible, with automatic fallback to Top Anime).
     */
    suspend fun getRandomAnime(
        excludeUniqueKeys: Set<String> = emptySet(),
    ): AnimeItem = withContext(Dispatchers.IO) {
        ensureSession()
        var fallbackCandidate: AnimeItem? = null
        repeat(3) {
            val picked = runCatchingCancellable {
                val page = inertiaGet("/random", component = "Anime", partialData = listOf("anime"))
                val animeObj = page["props"]?.jsonObject?.get("anime") as? JsonObject ?: return@runCatchingCancellable null
                val lang = AnimeUnityParser.pickPreferredSubLanguage(animeObj)
                AnimeUnityParser.parseSingleAnime(animeObj, variantLang = lang, forceDub = false)
            }.getOrNull()
            if (picked != null && picked.id.isNotBlank()) {
                fallbackCandidate = picked
                if (picked.uniqueKey !in excludeUniqueKeys) {
                    return@withContext picked
                }
            }
        }
        fallbackCandidate?.let { return@withContext it }

        val trending = fetchTrendingAnimes()
        val eligible = trending.filter { it.uniqueKey !in excludeUniqueKeys }.ifEmpty { trending }
        eligible.randomOrNull() ?: throw IOException("Impossibile pescare un anime casuale in questo momento")
    }

    suspend fun getAnimeDetails(animeId: String, isDub: Boolean): AnimeItem =
        getAnimeDetailsWithRelated(animeId, isDub).anime

    suspend fun getAnimeDetailsWithRelated(animeId: String, isDub: Boolean): AnimeDetailWithRelated =
        withContext(Dispatchers.IO) {
            val page = inertiaGet(
                path = "/anime/$animeId-x",
                component = "Anime",
                partialData = listOf("anime", "relatedAnimes"),
            )
            val props = page["props"]?.jsonObject
            val animeObj = props?.get("anime") as? JsonObject
                ?: throw IOException("Dettagli anime non disponibili")
            val lang = if (isDub) "ita" else AnimeUnityParser.pickPreferredSubLanguage(animeObj)
            val anime = AnimeUnityParser.parseSingleAnime(animeObj, variantLang = lang, forceDub = isDub)

            val relatedArr = props["relatedAnimes"] as? JsonArray
            val related = relatedArr?.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val relLang = if (isDub) "ita" else AnimeUnityParser.pickPreferredSubLanguage(obj)
                val variants = (obj["variants"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val hasRequestedDub = variants.any { it.str("language")?.equals("ita", ignoreCase = true) == true }
                val useDub = isDub && hasRequestedDub
                AnimeUnityParser.parseSingleAnime(
                    obj = obj,
                    variantLang = if (useDub) "ita" else relLang,
                    forceDub = useDub,
                )
            }.orEmpty()

            AnimeDetailWithRelated(anime = anime, relatedAnimes = related)
        }

    suspend fun getEpisodes(animeId: String, isDub: Boolean): List<EpisodeItem> = withContext(Dispatchers.IO) {
        ensureSession()
        var activeId = animeId
        val collected = LinkedHashMap<String, EpisodeItem>()
        var start: Int? = null
        val seenStarts = mutableSetOf<Int>()
        var retriedCanonical = false

        while (true) {
            val query = if (start != null) "?start=$start" else ""
            val req = Request.Builder()
                .url("$baseUrl/api/anime/$activeId/episodes$query")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "$baseUrl/")
                .build()

            val payload: JsonObject? = httpClients.sessionClient.newCall(req).execute().use { resp ->
                if (resp.code == 404 && !retriedCanonical) {
                    retriedCanonical = true
                    null
                } else if (!resp.isSuccessful) {
                    throw IOException("Errore HTTP ${resp.code} nel recupero episodi")
                } else {
                    json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                }
            }

            if (payload == null) {
                val resolved = getAnimeDetails(activeId, isDub)
                if (resolved.id != activeId) {
                    activeId = resolved.id
                    continue
                }
                throw IOException("Anime non trovato ($animeId)")
            }

            val arr = payload["episodes"]?.jsonArray ?: break
            if (arr.isEmpty()) break

            var addedAny = false
            for (el in arr) {
                val parsedEp = AnimeUnityParser.parseEpisodeObject(el as? JsonObject, isDub) ?: continue
                if (parsedEp.number !in collected) addedAny = true
                collected[parsedEp.number] = parsedEp
            }

            val curStart = payload.intVal("start") ?: 1
            val tabSize = payload.intVal("tab_size") ?: 120
            val total = payload.intVal("total") ?: 0
            val offset = payload.intVal("offset") ?: 1
            seenStarts.add(curStart)

            val nextStart = curStart + tabSize
            val maxStart = if (offset <= 1) total + offset - 1 else total
            if (tabSize <= 0 || nextStart > maxStart || nextStart in seenStarts || (start != null && !addedAny)) {
                break
            }
            start = nextStart
        }

        collected.values.sortedWith(compareBy { SearchUtils.episodeSortKey(it.number) })
    }

    suspend fun resolveStream(animeId: String, videoId: String): StreamResolution = withContext(Dispatchers.IO) {
        val pageData = inertiaGet(
            path = "/anime/$animeId-x/$videoId",
            component = "Anime",
            partialData = listOf("embedUrl", "video"),
        )
        val embedUrl = pageData["props"]?.jsonObject?.str("embedUrl")?.trim()
            ?.takeIf { isTrustedEmbedUrl(it) }
            ?: throw IOException("embedUrl VixCloud non valido o non trovato per questo episodio")

        vixCloudExtractor.extractStream(embedUrl = embedUrl, refererBaseUrl = baseUrl)
    }

    suspend fun fetchValidatedSkipSegments(
        anime: AnimeItem,
        episodeNum: String,
        episodeLengthSeconds: Int = 0,
    ): List<SkipSegment> {
        return skipTimeDataSource.fetchValidatedSkipSegments(anime, episodeNum, episodeLengthSeconds)
    }

    internal fun extractVixCloudTimestamps(embedHtml: String, runtimeSeconds: Int = 0): List<SkipSegment> {
        return vixCloudExtractor.extractVixCloudTimestamps(embedHtml, runtimeSeconds)
    }

    suspend fun resolveDirectDownload(animeId: String, videoId: String): DirectDownloadSource =
        withContext(Dispatchers.IO) {
            ensureSession()
            for (quality in listOf(1080, 720, 480)) {
                try {
                    val req = Request.Builder()
                        .url("$baseUrl/episode-videos/$videoId/download?quality=$quality")
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", "$baseUrl/")
                        .build()
                    httpClients.noRedirectSessionClient.newCall(req).execute().use { resp ->
                        val location = resp.header("Location")?.trim()
                        if (resp.code in 300..399 && !location.isNullOrBlank() &&
                            UrlSecurityPolicy.isTrustedMediaUrl(location)
                        ) {
                            return@withContext DirectDownloadSource(url = location, referer = "$baseUrl/")
                        }
                    }
                } catch (_: IOException) {
                    continue
                }
            }

            val resolved = resolveStream(animeId, videoId)
            val directUrl = resolved.downloadUrl
                ?: throw IOException("Download MP4 diretto non disponibile per questo episodio")
            DirectDownloadSource(url = directUrl, referer = resolved.referer)
        }

    internal fun parseAnimeVariants(obj: JsonObject): List<AnimeItem> =
        AnimeUnityParser.parseAnimeVariants(obj)
}
