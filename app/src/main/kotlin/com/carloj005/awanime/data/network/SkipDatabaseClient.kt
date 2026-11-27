package com.carloj005.awanime.data.network

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Client dedicato ai database crowdsourced di timestamp Intro/Outro/Recap
 * (AniSkip v2 REST + Anime-Skip GraphQL) con query parametrizzate tramite variabili JSON.
 */
class SkipDatabaseClient(
    externalClient: OkHttpClient,
    private val json: Json,
) {

    private val fastClient: OkHttpClient = externalClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    suspend fun fetchValidatedSkipSegments(
        anime: AnimeItem,
        episodeNum: String,
        episodeLengthSeconds: Int = 0,
    ): List<SkipSegment> = withContext(Dispatchers.IO) {
        val epInt = episodeNum.trim().split(".", "-").firstOrNull()?.toIntOrNull()
            ?: return@withContext emptyList()
        if (epInt <= 0) return@withContext emptyList()

        coroutineScope {
            val exactDeferred = async {
                if (anime.malId > 0 && episodeLengthSeconds > 0) {
                    queryAniSkipRaw(anime.malId, epInt, episodeLengthSeconds)
                } else {
                    emptyList()
                }
            }
            val globalDeferred = async {
                if (anime.malId > 0) {
                    queryAniSkipRaw(anime.malId, epInt, 0)
                } else {
                    emptyList()
                }
            }
            val animeSkipDeferred = async {
                queryAnimeSkipEpisode(anime, epInt, episodeLengthSeconds)
            }

            SkipTimeValidator.reconcileCrowdsourcedSegments(
                aniSkipExact = exactDeferred.await(),
                aniSkipGlobal = globalDeferred.await(),
                animeSkip = animeSkipDeferred.await(),
                realDurationSec = episodeLengthSeconds,
            )
        }
    }

    private fun queryAniSkipRaw(
        malId: Int,
        epInt: Int,
        episodeLengthSeconds: Int,
    ): List<RawSkipCandidate> {
        return try {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$epInt" +
                "?types=op&types=mixed-op&types=ed&types=mixed-ed&types=recap&episodeLength=$episodeLengthSeconds"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", AnimeUnityClient.USER_AGENT)
                .header("Accept", "application/json")
                .build()
            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                if (root.boolVal("found") != true) return emptyList()
                val results = root["results"] as? JsonArray ?: return emptyList()
                parseAniSkipResults(results)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseAniSkipResults(results: JsonArray): List<RawSkipCandidate> {
        val list = mutableListOf<RawSkipCandidate>()
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
            list.add(
                RawSkipCandidate(
                    kind = kind,
                    startMs = startMs,
                    endMs = endMs,
                    episodeLengthSec = obj.doubleVal("episodeLength"),
                    source = "aniskip",
                    isMixed = skipType.startsWith("mixed-"),
                )
            )
        }
        return list
    }

    private fun queryAnimeSkipEpisode(
        anime: AnimeItem,
        epInt: Int,
        realDurationSec: Int,
    ): AnimeSkipEpisodeResult {
        return try {
            val payload = buildAnimeSkipRequestBody(anime) ?: return AnimeSkipEpisodeResult()
            val req = Request.Builder()
                .url(ANIME_SKIP_GRAPHQL_URL)
                .header("User-Agent", AnimeUnityClient.USER_AGENT)
                .header("Accept", "application/json")
                .header("X-Client-ID", ANIME_SKIP_PUBLIC_CLIENT_ID)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return AnimeSkipEpisodeResult()
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val data = root["data"] as? JsonObject ?: return AnimeSkipEpisodeResult()
                val shows = (data["findShowsByExternalId"] ?: data["searchShows"]) as? JsonArray
                    ?: return AnimeSkipEpisodeResult()
                selectBestAnimeSkipEpisode(shows, epInt, realDurationSec)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            AnimeSkipEpisodeResult()
        }
    }

    private fun selectBestAnimeSkipEpisode(
        shows: JsonArray,
        epInt: Int,
        realDurationSec: Int,
    ): AnimeSkipEpisodeResult {
        val targetNum = epInt.toString()
        val matchingEps = mutableListOf<Pair<Double, List<AnimeSkipMarker>>>()
        for (showEl in shows) {
            val showObj = showEl as? JsonObject ?: continue
            val eps = showObj["episodes"] as? JsonArray ?: continue
            for (epEl in eps) {
                val epObj = epEl as? JsonObject ?: continue
                if (epObj.str("number")?.trim() != targetNum) continue
                val baseDur = epObj.doubleVal("baseDuration") ?: continue
                val tsArr = epObj["timestamps"] as? JsonArray ?: continue
                val markers = tsArr.mapNotNull { tEl ->
                    val tObj = tEl as? JsonObject ?: return@mapNotNull null
                    val at = tObj.doubleVal("at") ?: return@mapNotNull null
                    val typeName = (tObj["type"] as? JsonObject)?.str("name") ?: return@mapNotNull null
                    AnimeSkipMarker(atSec = at, typeName = typeName)
                }
                if (markers.isNotEmpty()) {
                    matchingEps.add(baseDur to markers)
                }
            }
        }
        if (matchingEps.isEmpty()) return AnimeSkipEpisodeResult()

        val best = if (realDurationSec > 0) {
            matchingEps.minByOrNull { abs(it.first - realDurationSec) }
        } else {
            matchingEps.maxByOrNull { it.second.size }
        } ?: return AnimeSkipEpisodeResult()

        return SkipTimeValidator.parseAnimeSkipEpisode(
            markers = best.second,
            baseDurationSec = best.first,
            realDurationSec = realDurationSec,
        )
    }

    companion object {
        private const val ANIME_SKIP_GRAPHQL_URL = "https://api.anime-skip.com/graphql"

        // Documented public read-only Client-ID published by Anime-Skip for open-source clients.
        private const val ANIME_SKIP_PUBLIC_CLIENT_ID = "th2oogUKrgOf1J8wMSIUPV0IpBMsLOJi"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val QUERY_BY_ANILIST =
            "query(\$serviceId: String!) { findShowsByExternalId(service: ANILIST, serviceId: \$serviceId) { id episodes { number baseDuration timestamps { at type { name } } } } }"

        private const val QUERY_BY_SEARCH =
            "query(\$search: String!) { searchShows(search: \$search, limit: 3) { id name episodes { number baseDuration timestamps { at type { name } } } } }"

        internal fun buildAnimeSkipRequestBody(anime: AnimeItem): String? {
            if (anime.anilistId > 0) {
                return buildJsonObject {
                    put("query", QUERY_BY_ANILIST)
                    putJsonObject("variables") {
                        put("serviceId", anime.anilistId.toString())
                    }
                }.toString()
            }
            val cleanTitle = SearchUtils.stripDubTags(anime.title)
                .replace(Regex("""[\r\n\t]+"""), " ")
                .trim()
                .take(80)
            if (cleanTitle.length < 3) return null

            return buildJsonObject {
                put("query", QUERY_BY_SEARCH)
                putJsonObject("variables") {
                    put("search", cleanTitle)
                }
            }.toString()
        }
    }
}
