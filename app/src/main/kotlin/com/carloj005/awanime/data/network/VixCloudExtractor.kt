package com.carloj005.awanime.data.network

import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import com.carloj005.awanime.data.model.StreamCandidate
import com.carloj005.awanime.data.model.StreamResolution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Estrattore dedicato per gli embed VixCloud:
 * - Verifica stretta degli host embed consentiti (anti-subdomain/phishing bypass)
 * - Risoluzione playlist HLS master e selezione della variante a risoluzione massima
 * - Estrazione URL MP4 diretto e timestamp acustici nativi di Intro/Outro/Recap
 */
class VixCloudExtractor(
    private val httpClient: OkHttpClient,
    private val json: Json,
) {

    fun resolveEmbed(embedUrl: String, referer: String): StreamResolution {
        if (!isTrustedEmbedUrl(embedUrl)) {
            throw IOException("embedUrl VixCloud non valido o non fidato")
        }

        val embedHtml = fetchEmbedHtml(embedUrl, referer)
        val embedOrigin = embedUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: "https://vixcloud.cc/"

        val candidates = mutableListOf<StreamCandidate>()
        val seenUrls = HashSet<String>()

        val hlsMaster = extractHlsMasterUrl(embedHtml)
        if (hlsMaster != null) {
            val best = resolveBestHlsVariant(hlsMaster, embedOrigin)
            if (best != null && seenUrls.add(best.first)) {
                candidates.add(StreamCandidate(url = best.first, isHls = true, qualityLabel = "${best.second} HLS"))
            }
            if (seenUrls.add(hlsMaster)) {
                candidates.add(StreamCandidate(url = hlsMaster, isHls = true, qualityLabel = "HLS AUTO"))
            }
        }

        val directUrl = extractDirectDownloadUrl(embedHtml)
        if (directUrl != null && seenUrls.add(directUrl)) {
            candidates.add(StreamCandidate(url = directUrl, isHls = false, qualityLabel = "MP4"))
        }

        if (candidates.isEmpty()) {
            throw IOException("Nessuno stream video disponibile per questo episodio")
        }

        val skipSegments = extractVixCloudTimestamps(embedHtml, runtimeSeconds = 0)
        return StreamResolution(
            candidates = candidates,
            downloadUrl = directUrl,
            referer = embedOrigin,
            qualityLabel = candidates.first().qualityLabel,
            skipSegments = skipSegments,
        )
    }

    private fun fetchEmbedHtml(embedUrl: String, referer: String): String {
        val req = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", AnimeUnityClient.USER_AGENT)
            .header("Referer", referer)
            .build()

        return httpClient.newCall(req).execute().use { resp ->
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
    }

    fun extractVixCloudTimestamps(embedHtml: String, runtimeSeconds: Int = 0): List<SkipSegment> {
        val match = TIMESTAMPS_REGEX.find(embedHtml) ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(match.groupValues[1]).jsonArray }.getOrNull()
            ?: return emptyList()
        val raw = mutableListOf<RawSkipCandidate>()
        for (el in arr) {
            val obj = el as? JsonObject ?: continue
            val kind = when (obj.str("kind")?.lowercase()) {
                "intro", "op", "opening" -> SkipKind.INTRO
                "outro", "ed", "ending" -> SkipKind.OUTRO
                "recap" -> SkipKind.RECAP
                else -> continue
            }
            val startSec = obj.doubleVal("start_time") ?: continue
            val endSec = obj.doubleVal("end_time") ?: continue
            raw.add(
                RawSkipCandidate(
                    kind = kind,
                    startMs = (startSec * 1000.0).toLong().coerceAtLeast(0L),
                    endMs = (endSec * 1000.0).toLong(),
                    source = "vixcloud",
                    confidence = obj.doubleVal("confidence"),
                )
            )
        }
        return SkipTimeValidator.validateVixCloudSegments(raw, runtimeSeconds)
    }

    private fun resolveBestHlsVariant(masterUrl: String, referer: String): Pair<String, String>? {
        return try {
            val req = Request.Builder()
                .url(masterUrl)
                .header("User-Agent", AnimeUnityClient.USER_AGENT)
                .header("Referer", referer)
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string().orEmpty()
                parseBestHlsVariant(text, resp.request.url)
            }
        } catch (_: IOException) {
            null
        }
    }

    companion object {
        private val TRUSTED_VIX_HOST_REGEX =
            Regex("""^(?:[a-z0-9-]+\.)*(?:vixcloud|vix-content|vixsrc)\.[a-z]{2,10}$""")
        private val TIMESTAMPS_REGEX = Regex("""\btimestamps\s*:\s*(\[[^\]]*\])""", RegexOption.DOT_MATCHES_ALL)
        private val DOWNLOAD_URL_REGEX =
            Regex("""window\.downloadUrl\s*=\s*(['"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
        private val MASTER_PLAYLIST_REGEX = Regex("""window\.masterPlaylist\s*=\s*\{""")
        private val PARAMS_BLOCK_REGEX = Regex("""\bparams\s*:\s*\{([^}]*)\}""")
        private val PLAYLIST_URL_REGEX = Regex("""\burl\s*:\s*(['"])(.*?)\1""")
        private val CAN_PLAY_FHD_REGEX = Regex("""window\.canPlayFHD\s*=\s*(?:true|1)\b""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("""\bRESOLUTION=(\d+)x(\d+)""")
        private val BANDWIDTH_REGEX = Regex("""\bBANDWIDTH=(\d+)""")

        fun isTrustedEmbedUrl(rawUrl: String): Boolean {
            val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            val host = parsed.host.lowercase()
            return TRUSTED_VIX_HOST_REGEX.matches(host) || AnimeUnityClient.isTrustedAnimeUnityOrigin(rawUrl)
        }

        fun extractDirectDownloadUrl(page: String): String? {
            val match = DOWNLOAD_URL_REGEX.find(page)
            val url = match?.groupValues?.getOrNull(2)?.trim()
            return url?.takeIf { it.startsWith("https://") }
        }

        fun extractHlsMasterUrl(page: String): String? {
            val start = MASTER_PLAYLIST_REGEX.find(page) ?: return null
            val tail = page.substring(start.range.last + 1)
            val paramsBlock = PARAMS_BLOCK_REGEX.find(tail)?.groupValues?.getOrNull(1) ?: return null
            val rawUrl = PLAYLIST_URL_REGEX.find(tail)?.groupValues?.getOrNull(2)?.trim()
                ?.takeIf { it.startsWith("https://") } ?: return null

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
            if (CAN_PLAY_FHD_REGEX.containsMatchIn(page)) {
                builder.setQueryParameter("h", "1")
            }
            return builder.build().toString()
        }

        internal fun parseBestHlsVariant(playlistText: String, baseHttp: HttpUrl): Pair<String, String>? {
            if (!playlistText.trimStart().startsWith("#EXTM3U")) return null
            val lines = playlistText.lines()
            if (lines.any { it.startsWith("#EXT-X-MEDIA:") }) return null

            data class Variant(val pixels: Long, val bw: Long, val height: Int, val url: String)
            val variants = mutableListOf<Variant>()

            for (i in 0 until lines.size - 1) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val res = RESOLUTION_REGEX.find(line)
                    val w = res?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    val h = res?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                    val bw = BANDWIDTH_REGEX.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
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
            return best.url to label
        }
    }
}
