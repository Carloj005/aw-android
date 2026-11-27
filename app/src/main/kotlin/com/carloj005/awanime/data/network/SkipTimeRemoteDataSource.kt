package com.carloj005.awanime.data.network

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.SkipSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.math.abs

/**
 * Fetches crowdsourced Intro/Outro/Recap segments in parallel from AniSkip v2 and Anime-Skip GraphQL,
 * delegating cross-source consensus to [SkipTimeValidator].
 */
class SkipTimeRemoteDataSource(
    private val fastHttpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    companion object {
        private const val ANISKIP_BASE_URL = "https://api.aniskip.com/v2/skip-times"
        private const val ANIME_SKIP_GRAPHQL_URL = "https://api.anime-skip.com/graphql"
        // Public web client identifier documented by Anime-Skip for read-only GraphQL queries
        private const val ANIME_SKIP_PUBLIC_CLIENT_ID = "th2oogUKrgOf1J8wMSIUPV0IpBMsLOJi"

        private const val QUERY_BY_ANILIST =
            """query(${'$'}serviceId: String!) { findShowsByExternalId(service: ANILIST, serviceId: ${'$'}serviceId) { id episodes { number baseDuration timestamps { at type { name } } } } }"""

        private const val QUERY_BY_SEARCH =
            """query(${'$'}search: String!) { searchShows(search: ${'$'}search, limit: 3) { id name episodes { number baseDuration timestamps { at type { name } } } } }"""
    }

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
            val url = "$ANISKIP_BASE_URL/$malId/$epInt" +
                "?types=op&types=mixed-op&types=ed&types=mixed-ed&types=recap&episodeLength=$episodeLengthSeconds"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", AnimeUnityClient.USER_AGENT)
                .header("Accept", "application/json")
                .build()
            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                if (root.boolVal("found") != true) return emptyList()
                val results = root["results"] as? JsonArray ?: return emptyList()
                results.mapNotNull { parseAniSkipCandidate(it as? JsonObject) }
            }
        } catch (_: IOException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseAniSkipCandidate(obj: JsonObject?): RawSkipCandidate? {
        if (obj == null) return null
        val skipType = obj.str("skipType")?.lowercase() ?: return null
        val kind = when (skipType) {
            "op", "mixed-op" -> SkipKind.INTRO
            "ed", "mixed-ed" -> SkipKind.OUTRO
            "recap" -> SkipKind.RECAP
            else -> return null
        }
        val interval = obj["interval"] as? JsonObject ?: return null
        val startMs = ((interval.doubleVal("startTime") ?: return null) * 1000.0).toLong().coerceAtLeast(0L)
        val endMs = ((interval.doubleVal("endTime") ?: return null) * 1000.0).toLong()
        return RawSkipCandidate(
            kind = kind,
            startMs = startMs,
            endMs = endMs,
            episodeLengthSec = obj.doubleVal("episodeLength"),
            source = "aniskip",
            isMixed = skipType.startsWith("mixed-"),
        )
    }

    private fun queryAnimeSkipEpisode(
        anime: AnimeItem,
        epInt: Int,
        realDurationSec: Int,
    ): AnimeSkipEpisodeResult {
        return try {
            val requestPayload = buildAnimeSkipGraphQlPayload(anime) ?: return AnimeSkipEpisodeResult()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val req = Request.Builder()
                .url(ANIME_SKIP_GRAPHQL_URL)
                .header("User-Agent", AnimeUnityClient.USER_AGENT)
                .header("Accept", "application/json")
                .header("X-Client-ID", ANIME_SKIP_PUBLIC_CLIENT_ID)
                .post(requestPayload.toRequestBody(mediaType))
                .build()

            fastHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return AnimeSkipEpisodeResult()
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val data = root["data"] as? JsonObject ?: return AnimeSkipEpisodeResult()
                val shows = (data["findShowsByExternalId"] ?: data["searchShows"]) as? JsonArray
                    ?: return AnimeSkipEpisodeResult()

                val targetNum = epInt.toString()
                val matchingEps = extractMatchingEpisodes(shows, targetNum)
                if (matchingEps.isEmpty()) return AnimeSkipEpisodeResult()

                val best = if (realDurationSec > 0) {
                    matchingEps.minByOrNull { abs(it.first - realDurationSec) }
                } else {
                    matchingEps.maxByOrNull { it.second.size }
                } ?: return AnimeSkipEpisodeResult()

                SkipTimeValidator.parseAnimeSkipEpisode(
                    markers = best.second,
                    baseDurationSec = best.first,
                    realDurationSec = realDurationSec,
                )
            }
        } catch (_: IOException) {
            AnimeSkipEpisodeResult()
        } catch (_: Exception) {
            AnimeSkipEpisodeResult()
        }
    }

    internal fun buildAnimeSkipGraphQlPayload(anime: AnimeItem): String? {
        val bodyObj = if (anime.anilistId > 0) {
            buildJsonObject {
                put("query", QUERY_BY_ANILIST)
                putJsonObject("variables") {
                    put("serviceId", anime.anilistId.toString())
                }
            }
        } else {
            val cleanTitle = SearchUtils.stripDubTags(anime.title).trim().take(80)
            if (cleanTitle.length < 3) return null
            buildJsonObject {
                put("query", QUERY_BY_SEARCH)
                putJsonObject("variables") {
                    put("search", cleanTitle)
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), bodyObj)
    }

    private fun extractMatchingEpisodes(
        shows: JsonArray,
        targetNum: String,
    ): List<Pair<Double, List<AnimeSkipMarker>>> {
        val matching = mutableListOf<Pair<Double, List<AnimeSkipMarker>>>()
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
                    matching.add(baseDur to markers)
                }
            }
        }
        return matching
    }

    private fun JsonObject.str(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.content
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
