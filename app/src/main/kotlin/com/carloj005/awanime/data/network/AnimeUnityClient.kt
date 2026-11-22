package com.carloj005.awanime.data.network

import android.content.Context
import android.net.Uri
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.LatestFilter
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.StreamResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client nativo Kotlin per il nuovo AnimeUnity (https://animeunity.cx, Laravel + Inertia.js + API REST),
 * con:
 * - Auto-risoluzione dominio e scansione TLD di emergenza + verifica anti-phishing Inertia
 * - DNS-over-HTTPS automatico contro blocchi DNS ISP
 * - Espansione varianti lingua (SUB / ITA / Cinese / Coreano / Inglese)
 * - Ordinamento cronologico delle stagioni e ricerca fuzzy tollerante ai refusi
 * - Streaming 1080p FHD HLS diretto su VixCloud + fallback MP4 diretto
 */
class AnimeUnityClient(context: Context?) {

    companion object {
        const val DEFAULT_BASE_URL = "https://animeunity.cx"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val CANDIDATE_TLDS = listOf(
            "cx", "co", "so", "to", "tv", "it", "cc", "io", "me", "ac", "org", "net",
        )

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

        private const val PREFS_NAME = "aw_anime_prefs"
        private const val KEY_BASE_URL = "animeunity_base_url"

        fun mapStatus(raw: String?): String = when (raw?.trim()?.lowercase()) {
            "ongoing", "in corso" -> "In corso"
            "completed", "terminato", "finito" -> "Finito"
            "upcoming", "not_yet_released", "non rilasciato" -> "Non rilasciato"
            else -> "Sconosciuto"
        }

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
            val host = parsed.host.lowercase()
            return Regex("""^(?:www\.)?animeunity\.[a-z]{2,10}$""").matches(host)
        }

        fun isTrustedEmbedUrl(rawUrl: String): Boolean {
            val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            val host = parsed.host.lowercase()
            return host == "vixcloud.cc" || host.endsWith(".vixcloud.cc") ||
                host.contains("vixcloud.") || host.contains("vix-content.") ||
                host.contains("vixsrc.") || isTrustedAnimeUnityOrigin(rawUrl)
        }
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

    private val httpsOnlyInterceptor = okhttp3.Interceptor { chain ->
        val req = chain.request()
        if (req.url.scheme != "https") {
            throw IOException("Connessione HTTP non cifrata bloccata: ${req.url.host}")
        }
        chain.proceed(req)
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dohDns)
        .cookieJar(cookieJar)
        .addInterceptor(httpsOnlyInterceptor)
        .followRedirects(true)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Client isolato SENZA cookie di sessione AnimeUnity per chiamate a domini terzi (VixCloud, AniSkip). */
    val externalOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dohDns)
        .addInterceptor(httpsOnlyInterceptor)
        .followRedirects(true)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    val noRedirectClient: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** OkHttpClient dedicato a ExoPlayer e download video (senza cookie, HTTPS-only + DoH). */
    val mediaOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dohDns)
        .addInterceptor(httpsOnlyInterceptor)
        .followRedirects(true)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    val imageOkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(dohDns)
        .addInterceptor(httpsOnlyInterceptor)
        .followRedirects(true)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun extractInertiaPage(html: String): JsonObject? {
        val m = Regex("""data-page="([^"]+)"""").find(html) ?: return null
        val decoded = decodeHtmlEntities(m.groupValues[1])
        return runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
    }

    suspend fun ensureSession(forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        if (sessionReady && inertiaVersion.isNotBlank() && !forceRefresh) return@withContext
        sessionMutex.withLock {
            if (sessionReady && inertiaVersion.isNotBlank() && !forceRefresh) return@withLock

            val candidates = linkedSetOf(baseUrl, DEFAULT_BASE_URL, "https://www.animeunity.cx")
            for (tld in CANDIDATE_TLDS) {
                candidates.add("https://animeunity.$tld")
                candidates.add("https://www.animeunity.$tld")
            }

            for (cand in candidates) {
                try {
                    val req = Request.Builder()
                        .url("$cand/")
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
                        .build()
                    okHttpClient.newCall(req).execute().use { resp ->
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
                                    parseAnimeVariants(obj).firstOrNull()
                                }
                            }
                            sessionReady = true
                            return@withLock
                        }
                    }
                } catch (_: Exception) {
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
            val payload = apiGetJson("/api/top-anime?page=1").jsonObject
            val arr = payload["data"]?.jsonArray ?: JsonArray(emptyList())
            return@withContext arr.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val expanded = parseAnimeVariants(obj)
                val primary = expanded.firstOrNull() ?: return@mapNotNull null
                primary.copy(
                    latestEpisodeNum = if (primary.releasedEpisodes > 0) primary.releasedEpisodes.toString() else "1",
                )
            }
        }

        val langQuery = when (filter) {
            LatestFilter.SUB -> "&language=sub"
            LatestFilter.DUB -> "&language=dub"
            else -> ""
        }
        val collected = mutableListOf<AnimeItem>()
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
                val isDub = lang == "ita"
                val parsed = parseSingleAnime(
                    obj = animeObj,
                    variantLang = lang,
                    forceDub = isDub,
                    latestEpNum = epNum,
                    latestVideoId = videoId,
                )
                collected.add(parsed)
            }
            val lastPage = payload.intVal("last_page") ?: 1
            if (page >= lastPage) break
        }
        collected
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
        } catch (e: Exception) {
            if (mergedById.isEmpty()) throw e
        }

        val expanded = mutableListOf<AnimeItem>()
        for (rec in mergedById.values) {
            expanded.addAll(parseAnimeVariants(rec))
        }
        return expanded
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
            val subHits = runCatching { querySearchRecords(subQuery) }.getOrDefault(emptyList())
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
        val lang = if (isDub) "ita" else pickPreferredSubLanguage(animeObj)
        parseSingleAnime(animeObj, variantLang = lang, forceDub = isDub)
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

            var addedAny = false
            for (el in arr) {
                val epObj = el as? JsonObject ?: continue
                val num = epObj.str("number")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                val epId = epObj.str("id").orEmpty()
                val isFiller = epObj.boolVal("is_filler") ?: false
                val videos = epObj["videos"]?.jsonArray ?: continue
                val chosenVideo = pickEpisodeVideo(videos, wantDub = isDub) ?: continue
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

        val embedReq = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$baseUrl/")
            .build()

        val embedHtml = externalOkHttpClient.newCall(embedReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Errore VixCloud embed HTTP ${resp.code}")
            }
            val text = resp.body?.string().orEmpty()
            if ("canPlayFHD=1" in embedUrl && "canPlayFHD" !in text) {
                "$text\nwindow.canPlayFHD = true;\n"
            } else {
                text
            }
        }

        val candidates = mutableListOf<String>()
        var qualityLabel = "HD"

        val hlsMaster = extractHlsMasterUrl(embedHtml)
        if (hlsMaster != null) {
            val best = resolveBestHlsVariant(hlsMaster)
            if (best != null) {
                candidates.add(best.first)
                qualityLabel = best.second
            }
            if (hlsMaster !in candidates) {
                candidates.add(hlsMaster)
            }
        }

        val directUrl = extractDirectDownloadUrl(embedHtml)
        if (directUrl != null && directUrl !in candidates) {
            candidates.add(directUrl)
        }

        if (candidates.isEmpty()) {
            throw IOException("Nessuno stream video disponibile per questo episodio")
        }

        val skipSegments = extractVixCloudTimestamps(embedHtml)

        StreamResolution(
            candidates = candidates,
            downloadUrl = directUrl,
            referer = "https://vixcloud.cc/",
            qualityLabel = qualityLabel,
            skipSegments = skipSegments,
        )
    }

    /**
     * Estrae i timestamp reali di Intro / Outro direttamente dall'HTML embed di VixCloud
     * (`timestamps: [{"kind":"intro","start_time":59.9,"end_time":147.9,...}, ...]`).
     */
    internal fun extractVixCloudTimestamps(embedHtml: String): List<SkipSegment> {
        val m = Regex("""\btimestamps\s*:\s*(\[[^\]]*\])""", RegexOption.DOT_MATCHES_ALL).find(embedHtml)
            ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(m.groupValues[1]).jsonArray }.getOrNull()
            ?: return emptyList()
        val segments = mutableListOf<SkipSegment>()
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val kindStr = obj.str("kind")?.lowercase() ?: continue
            val kind = when (kindStr) {
                "intro", "op", "opening" -> SkipKind.INTRO
                "outro", "ed", "ending" -> SkipKind.OUTRO
                "recap" -> SkipKind.RECAP
                else -> continue
            }
            val startSec = obj.doubleVal("start_time") ?: continue
            val endSec = obj.doubleVal("end_time") ?: continue
            val startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L)
            val endMs = (endSec * 1000.0).toLong()
            if (endMs > startMs + 3_000L) {
                segments.add(
                    SkipSegment(
                        kind = kind,
                        startMs = startMs,
                        endMs = endMs,
                        source = "vixcloud",
                    )
                )
            }
        }
        return segments.sortedBy { it.startMs }
    }

    /**
     * Fallback via AniSkip API v2 usando `malId` quando VixCloud non espone timestamp rilevati.
     */
    suspend fun fetchAniSkipSegments(
        malId: Int,
        episodeNum: String,
        episodeLengthSeconds: Int = 0,
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        if (malId <= 0) return@withContext emptyList()
        val epInt = episodeNum.trim().split(".", "-").firstOrNull()?.toIntOrNull()
            ?: return@withContext emptyList()
        if (epInt <= 0) return@withContext emptyList()

        try {
            val lenQuery = if (episodeLengthSeconds > 0) "&episodeLength=$episodeLengthSeconds" else "&episodeLength=0"
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$epInt?types=op&types=ed&types=recap$lenQuery"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            val fastClient = externalOkHttpClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                if (root.boolVal("found") != true) return@withContext emptyList()
                val results = root["results"] as? JsonArray ?: return@withContext emptyList()
                val list = mutableListOf<SkipSegment>()
                for (el in results) {
                    val obj = el as? JsonObject ?: continue
                    val skipType = obj.str("skipType")?.lowercase() ?: continue
                    val kind = when (skipType) {
                        "op", "mixed-op" -> SkipKind.INTRO
                        "ed", "mixed-ed" -> SkipKind.OUTRO
                        "recap" -> SkipKind.RECAP
                        else -> continue
                    }
                    val interval = obj["interval"] as? JsonObject ?: continue
                    val startMs = ((interval.doubleVal("startTime") ?: continue) * 1000.0).toLong().coerceAtLeast(0L)
                    val endMs = ((interval.doubleVal("endTime") ?: continue) * 1000.0).toLong()
                    if (endMs > startMs + 3_000L) {
                        list.add(SkipSegment(kind = kind, startMs = startMs, endMs = endMs, source = "aniskip"))
                    }
                }
                list.sortedBy { it.startMs }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun resolveDirectDownloadUrl(animeId: String, videoId: String): String = withContext(Dispatchers.IO) {
        ensureSession()
        // 1. Prova il nuovo endpoint diretto di AnimeUnity /episode-videos/{id}/download?quality=1080|720|480
        for (q in listOf(1080, 720, 480)) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/episode-videos/$videoId/download?quality=$q")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$baseUrl/")
                    .build()
                noRedirectClient.newCall(req).execute().use { resp ->
                    val location = resp.header("Location")?.trim()
                    if (resp.code in 300..399 && !location.isNullOrBlank() && location.startsWith("https://")) {
                        return@withContext location
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        // 2. Fallback: window.downloadUrl dall'embed VixCloud
        val resolved = resolveStream(animeId, videoId)
        resolved.downloadUrl
            ?: throw IOException("Download MP4 diretto non disponibile per questo episodio")
    }

    private fun extractDirectDownloadUrl(page: String): String? {
        val m = Regex("""window\.downloadUrl\s*=\s*(['"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL).find(page)
        val url = m?.groupValues?.getOrNull(2)?.trim()
        return url?.takeIf { it.startsWith("https://") }
    }

    private fun extractHlsMasterUrl(page: String): String? {
        val start = Regex("""window\.masterPlaylist\s*=\s*\{""").find(page) ?: return null
        val tail = page.substring(start.range.last + 1)
        val paramsBlock = Regex("""\bparams\s*:\s*\{([^}]*)\}""").find(tail)?.groupValues?.getOrNull(1) ?: return null
        val rawUrl = Regex("""\burl\s*:\s*(['"])(.*?)\1""").find(tail)?.groupValues?.getOrNull(2)?.trim()
            ?.takeIf { it.startsWith("http") } ?: return null

        fun param(name: String): String? {
            return Regex("""(?:['"]?$name['"]?)\s*:\s*(['"])(.*?)\1""")
                .find(paramsBlock)?.groupValues?.getOrNull(2)
        }

        val token = param("token")?.takeIf { it.isNotEmpty() } ?: return null
        val expires = param("expires")?.takeIf { it.isNotEmpty() } ?: return null
        val asn = param("asn").orEmpty()

        val httpUrl = rawUrl.toHttpUrlOrNull() ?: return null
        val builder = httpUrl.newBuilder()
            .setQueryParameter("token", token)
            .setQueryParameter("expires", expires)
        if (asn.isNotEmpty()) builder.setQueryParameter("asn", asn)
        if (Regex("""window\.canPlayFHD\s*=\s*(?:true|1)\b""", RegexOption.IGNORE_CASE).containsMatchIn(page)) {
            builder.setQueryParameter("h", "1")
        }
        return builder.build().toString()
    }

    /**
     * Risolve la variante HLS alla massima risoluzione (es. 1920x1080 FHD).
     * Ritorna Pair(variantUrl, qualityLabel).
     */
    private fun resolveBestHlsVariant(masterUrl: String): Pair<String, String>? {
        return try {
            val req = Request.Builder()
                .url(masterUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://vixcloud.cc/")
                .build()
            externalOkHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                if (!text.trimStart().startsWith("#EXTM3U")) return null
                val lines = text.lines()
                if (lines.any { it.startsWith("#EXT-X-MEDIA:") }) return null

                data class Variant(val pixels: Long, val bw: Long, val height: Int, val url: String)
                val variants = mutableListOf<Variant>()
                val baseHttp = resp.request.url

                for (i in 0 until lines.size - 1) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val res = Regex("""\bRESOLUTION=(\d+)x(\d+)""").find(line)
                        val w = res?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        val h = res?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                        val bw = Regex("""\bBANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                        val next = lines[i + 1].trim()
                        if (next.isNotEmpty() && !next.startsWith("#")) {
                            val resolvedUrl = baseHttp.resolve(next)?.toString() ?: next
                            variants.add(Variant(w * h.toLong(), bw, h, resolvedUrl))
                        }
                    }
                }
                val best = variants.maxWithOrNull(compareBy<Variant>({ it.pixels }, { it.bw })) ?: return null
                val label = when {
                    best.height >= 1080 -> "1080p FHD"
                    best.height >= 720 -> "720p HD"
                    best.height > 0 -> "${best.height}p"
                    else -> "MAX"
                }
                best.url to label
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pickPreferredSubLanguage(obj: JsonObject): String {
        val variants = obj["variants"] as? JsonArray ?: return "jpn"
        val nonIta = variants.mapNotNull { it as? JsonObject }
            .mapNotNull { it.str("language")?.lowercase() }
            .filter { it != "ita" }
        return if ("jpn" in nonIta) "jpn" else (nonIta.firstOrNull() ?: "jpn")
    }

    private fun pickEpisodeVideo(videos: JsonArray, wantDub: Boolean): JsonObject? {
        val objs = videos.mapNotNull { it as? JsonObject }
        val visible = objs.filter { it.boolVal("is_hidden") != true }.ifEmpty { objs }
        if (visible.isEmpty()) return null
        if (wantDub) {
            return visible.firstOrNull { it.str("language")?.lowercase() == "ita" }
        }
        val nonIta = visible.filter { it.str("language")?.lowercase() != "ita" }
        return nonIta.firstOrNull { it.str("language")?.lowercase() == "jpn" } ?: nonIta.firstOrNull()
    }

    internal fun parseAnimeVariants(obj: JsonObject): List<AnimeItem> {
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

    internal fun parseSingleAnime(
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
        val genres = (obj["genres"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotBlank() }
        }.orEmpty()
        val studios = (obj["studios"] as? JsonArray)?.mapNotNull {
            (it as? JsonObject)?.str("name")?.takeIf { n -> n.isNotBlank() }
        }.orEmpty()

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

    private fun JsonObject.str(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.content
    }

    private fun JsonObject.intVal(key: String): Int? {
        val el = this[key] as? JsonPrimitive ?: return null
        return el.intOrNull ?: el.content.toIntOrNull()
    }

    private fun JsonObject.longVal(key: String): Long? {
        val el = this[key] as? JsonPrimitive ?: return null
        return el.longOrNull ?: el.content.toLongOrNull()
    }

    private fun JsonObject.boolVal(key: String): Boolean? {
        val el = this[key] as? JsonPrimitive ?: return null
        return el.booleanOrNull
    }

    private fun JsonObject.doubleVal(key: String): Double? {
        val el = this[key] as? JsonPrimitive ?: return null
        return el.doubleOrNull ?: el.content.toDoubleOrNull()
    }
}
