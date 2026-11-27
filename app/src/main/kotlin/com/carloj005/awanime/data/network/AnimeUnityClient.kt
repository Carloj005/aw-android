package com.carloj005.awanime.data.network

import android.content.Context
import android.net.Uri
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.StreamResolution
import com.carloj005.awanime.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
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
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client nativo Kotlin per AnimeUnity (Laravel + Inertia.js + API REST),
 * con auto-risoluzione dominio, DNS-over-HTTPS e delega modulare a:
 * - [AnimeUnityParser] per il parsing dei payload JSON
 * - [VixCloudExtractor] per l'estrazione stream HLS/MP4 e timestamp VixCloud
 * - [SkipDatabaseClient] per la validazione incrociata AniSkip / Anime-Skip
 */
class AnimeUnityClient(context: Context?) {

    companion object {
        const val DEFAULT_BASE_URL = "https://animeunity.cx"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val CANDIDATE_TLDS = listOf(
            "cx", "co", "so", "to", "tv", "it", "cc", "io", "me", "ac", "org", "net",
        )

        private const val PREFS_NAME = "aw_anime_prefs"
        private const val KEY_BASE_URL = "animeunity_base_url"

        private val TRUSTED_ORIGIN_REGEX = Regex("""^(?:www\.)?animeunity\.[a-z]{2,10}$""")
        private val DATA_PAGE_REGEX = Regex("""data-page="([^"]+)"""")

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

        fun isTrustedAnimeUnityOrigin(rawUrl: String): Boolean {
            val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            return TRUSTED_ORIGIN_REGEX.matches(parsed.host.lowercase())
        }

        fun isTrustedEmbedUrl(rawUrl: String): Boolean = VixCloudExtractor.isTrustedEmbedUrl(rawUrl)
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dohDns = DohFallbackDns()
    private val cookieJar = MemoryCookieJar()
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

    private val httpsOnlyInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.url.scheme != "https") {
            throw IOException("Connessione HTTP non cifrata bloccata: ${req.url.host}")
        }
        chain.proceed(req)
    }

    // Client base condiviso via newBuilder() per riutilizzare il Dispatcher senza duplicare thread pool.
    private val baseHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dohDns)
        .addInterceptor(httpsOnlyInterceptor)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    val okHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .cookieJar(cookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Client isolato SENZA cookie di sessione AnimeUnity per chiamate a domini terzi (VixCloud, AniSkip). */
    val externalOkHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    val noRedirectClient: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** OkHttpClient dedicato a ExoPlayer (senza cookie, pool compatto anti-503 su nginx, HTTPS-only + DoH). */
    val mediaOkHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** OkHttpClient separato per i download MP4 in background (evita contesa col player). */
    val downloadOkHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    val imageOkHttpClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val vixCloudExtractor = VixCloudExtractor(externalOkHttpClient, json)
    private val skipDatabaseClient = SkipDatabaseClient(externalOkHttpClient, json)

    private fun extractInertiaPage(html: String): JsonObject? {
        val match = DATA_PAGE_REGEX.find(html) ?: return null
        val decoded = decodeHtmlEntities(match.groupValues[1])
        return runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
    }

    suspend fun ensureSession(forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        if (sessionReady && inertiaVersion.isNotBlank() && !forceRefresh) return@withContext
        sessionMutex.withLock {
            if (sessionReady && inertiaVersion.isNotBlank() && !forceRefresh) return@withLock

            for (candidate in buildCandidateOrigins()) {
                try {
                    if (tryBootstrapSession(candidate)) return@withLock
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    continue
                }
            }
            throw IOException("Impossibile raggiungere AnimeUnity ($baseUrl). Controlla la connessione internet.")
        }
    }

    private fun buildCandidateOrigins(): Set<String> {
        val candidates = linkedSetOf(baseUrl, DEFAULT_BASE_URL, "https://www.animeunity.cx")
        for (tld in CANDIDATE_TLDS) {
            candidates.add("https://animeunity.$tld")
            candidates.add("https://www.animeunity.$tld")
        }
        return candidates
    }

    private fun tryBootstrapSession(origin: String): Boolean {
        val req = Request.Builder()
            .url("$origin/")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
            .build()
        okHttpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || !isValidAnimeUnityHtml(body)) return false

            val finalOrigin = "${resp.request.url.scheme}://${resp.request.url.host}"
            if (!isTrustedAnimeUnityOrigin(finalOrigin)) return false

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
            return true
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
            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
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
        okHttpClient.newCall(req).execute().use { resp ->
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

    suspend fun getLatestEpisodes(filter: LatestFilter): List<AnimeItem> = withContext(Dispatchers.IO) {
        ensureSession()
        if (filter == LatestFilter.TRENDING) {
            return@withContext fetchTrendingAnime()
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
                val itemObj = elem as? JsonObject ?: continue
                val epObj = itemObj["episode"] as? JsonObject ?: continue
                val animeObj = epObj["anime"] as? JsonObject ?: continue
                val videoId = itemObj.str("id") ?: continue
                val epNum = epObj.str("number") ?: "1"
                val lang = itemObj.str("language")?.lowercase() ?: "jpn"
                val parsed = AnimeUnityParser.parseSingleAnime(
                    obj = animeObj,
                    variantLang = lang,
                    forceDub = lang == "ita",
                    latestEpNum = epNum,
                    latestVideoId = videoId,
                )
                val dedupeKey = "${parsed.uniqueKey}_${parsed.latestVideoId ?: parsed.latestEpisodeNum ?: ""}"
                if (seenLatestKeys.add(dedupeKey)) {
                    collected.add(parsed)
                }
            }
            val lastPage = payload.intVal("last_page") ?: 1
            if (page >= lastPage) break
        }
        collected
    }

    private fun fetchTrendingAnime(): List<AnimeItem> {
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
        val encoded = Uri.encode(query)
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // fallback to /api/search
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (mergedById.isEmpty()) throw e
        }

        return mergedById.values.flatMap { AnimeUnityParser.parseAnimeVariants(it) }
    }

    suspend fun search(rawQuery: String): List<AnimeItem> = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return@withContext emptyList()
        ensureSession()

        val primary = querySearchRecords(query)
        if (primary.isNotEmpty()) {
            return@withContext SearchUtils.sortAnimeItems(primary, query)
        }

        // Fallback multi-token / fuzzy per tollerare refusi (es. "jojo steal ball run")
        val fallbackByKey = LinkedHashMap<String, AnimeItem>()
        for (subQuery in SearchUtils.generateFallbackQueries(query)) {
            val subHits = runCatchingCancellable { querySearchRecords(subQuery) }.getOrDefault(emptyList())
            for (item in subHits) {
                fallbackByKey.putIfAbsent(item.uniqueKey, item)
            }
        }
        if (fallbackByKey.isEmpty()) return@withContext emptyList()

        val chronologicallyOrdered = SearchUtils.sortAnimeItems(fallbackByKey.values.toList(), query)
        SearchUtils.rankFuzzyCandidates(query, chronologicallyOrdered)
    }

    suspend fun getAnimeDetails(animeId: String, isDub: Boolean): AnimeItem = withContext(Dispatchers.IO) {
        val page = inertiaGet("/anime/$animeId-x", component = "Anime", partialData = listOf("anime"))
        val animeObj = page["props"]?.jsonObject?.get("anime") as? JsonObject
            ?: throw IOException("Dettagli anime non disponibili")
        val lang = if (isDub) "ita" else AnimeUnityParser.pickPreferredSubLanguage(animeObj)
        AnimeUnityParser.parseSingleAnime(animeObj, variantLang = lang, forceDub = isDub)
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

            val payload: JsonObject? = okHttpClient.newCall(req).execute().use { resp ->
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

            val addedAny = collectEpisodesPage(arr, isDub, collected)
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

    private fun collectEpisodesPage(
        arr: JsonArray,
        isDub: Boolean,
        collected: MutableMap<String, EpisodeItem>,
    ): Boolean {
        var addedAny = false
        for (el in arr) {
            val epObj = el as? JsonObject ?: continue
            val num = epObj.str("number")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val epId = epObj.str("id").orEmpty()
            val isFiller = epObj.boolVal("is_filler") ?: false
            val videos = epObj["videos"]?.jsonArray ?: continue
            val chosenVideo = AnimeUnityParser.pickEpisodeVideo(videos, wantDub = isDub) ?: continue
            val videoId = chosenVideo.str("id") ?: continue
            if (num !in collected) addedAny = true
            collected[num] = EpisodeItem(
                episodeId = epId,
                number = num,
                videoId = videoId,
                language = chosenVideo.str("language")?.lowercase() ?: if (isDub) "ita" else "jpn",
                runtimeSeconds = chosenVideo.intVal("runtime") ?: 0,
                scwsId = chosenVideo.str("scws_id").orEmpty(),
                isFiller = isFiller,
            )
        }
        return addedAny
    }

    suspend fun resolveStream(animeId: String, videoId: String): StreamResolution = withContext(Dispatchers.IO) {
        val pageData = inertiaGet(
            path = "/anime/$animeId-x/$videoId",
            component = "Anime",
            partialData = listOf("embedUrl", "video"),
        )
        val embedUrl = pageData["props"]?.jsonObject?.str("embedUrl")?.trim()
            ?.takeIf { VixCloudExtractor.isTrustedEmbedUrl(it) }
            ?: throw IOException("embedUrl VixCloud non valido o non trovato per questo episodio")

        vixCloudExtractor.resolveEmbed(embedUrl = embedUrl, referer = "$baseUrl/")
    }

    internal fun extractVixCloudTimestamps(embedHtml: String, runtimeSeconds: Int = 0): List<SkipSegment> {
        return vixCloudExtractor.extractVixCloudTimestamps(embedHtml, runtimeSeconds)
    }

    suspend fun fetchValidatedSkipSegments(
        anime: AnimeItem,
        episodeNum: String,
        episodeLengthSeconds: Int = 0,
    ): List<SkipSegment> {
        return skipDatabaseClient.fetchValidatedSkipSegments(anime, episodeNum, episodeLengthSeconds)
    }

    suspend fun resolveDirectDownloadUrl(animeId: String, videoId: String): String = withContext(Dispatchers.IO) {
        ensureSession()
        for (quality in listOf(1080, 720, 480)) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/episode-videos/$videoId/download?quality=$quality")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$baseUrl/")
                    .build()
                noRedirectClient.newCall(req).execute().use { resp ->
                    val location = resp.header("Location")?.trim()
                    if (resp.code in 300..399 && !location.isNullOrBlank() && location.startsWith("https://")) {
                        return@withContext location
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                continue
            }
        }
        val resolved = resolveStream(animeId, videoId)
        resolved.downloadUrl
            ?: throw IOException("Download MP4 diretto non disponibile per questo episodio")
    }

    internal fun parseAnimeVariants(obj: JsonObject): List<AnimeItem> {
        return AnimeUnityParser.parseAnimeVariants(obj)
    }

    internal fun parseSingleAnime(
        obj: JsonObject,
        variantLang: String,
        forceDub: Boolean,
        latestEpNum: String? = null,
        latestVideoId: String? = null,
    ): AnimeItem {
        return AnimeUnityParser.parseSingleAnime(obj, variantLang, forceDub, latestEpNum, latestVideoId)
    }
}
