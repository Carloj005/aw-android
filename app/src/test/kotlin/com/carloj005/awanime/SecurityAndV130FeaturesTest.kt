package com.carloj005.awanime

import com.carloj005.awanime.data.integrations.AnimeUnityDeepLink
import com.carloj005.awanime.data.integrations.AnimeUnityDeepLinkParser
import com.carloj005.awanime.data.integrations.SuiteTvIntegration
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.EpisodeProgress
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.formatBytes
import com.carloj005.awanime.data.model.formatTimeMs
import com.carloj005.awanime.data.network.AnimeUnityParser
import com.carloj005.awanime.data.network.DohFallbackDns
import com.carloj005.awanime.data.network.MemoryCookieJar
import com.carloj005.awanime.data.network.UrlSecurityPolicy
import com.carloj005.awanime.data.notifications.EpisodeSnapshot
import com.carloj005.awanime.data.notifications.FavoriteEpisodeUpdateDetector
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.update.AppUpdater
import com.carloj005.awanime.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Inet6Address
import java.nio.file.Files
import java.util.Locale

class SecurityAndV130FeaturesTest {

    @Test
    fun appUpdater_rejectsBroadGithubUserContentSubdomains() {
        assertTrue(AppUpdater.isTrustedGitHubHost("api.github.com"))
        assertTrue(AppUpdater.isTrustedGitHubHost("github.com"))
        assertTrue(AppUpdater.isTrustedGitHubHost("objects.githubusercontent.com"))
        assertTrue(AppUpdater.isTrustedGitHubHost("release-assets.githubusercontent.com"))
        assertFalse(AppUpdater.isTrustedGitHubHost("raw.githubusercontent.com"))
        assertFalse(AppUpdater.isTrustedGitHubHost("evil.githubusercontent.com"))
        assertFalse(UrlSecurityPolicy.isTrustedGitHubDownloadUrl("https://evil.githubusercontent.com/fake.apk"))
    }

    @Test
    fun dohFallbackDns_blocksIpv6MappedNat64DiscardAndCompatPrivateAddresses() {
        assertTrue(DohFallbackDns.isTargetHostname("animeunity.cx"))
        assertTrue(DohFallbackDns.isTargetHostname("au-u1-01.vixcloud.cc"))
        assertFalse(DohFallbackDns.isTargetHostname("vixcloud.evil.com"))

        // ::ffff:127.0.0.1
        val mappedLoopback = Inet6Address.getByAddress(
            "mapped-loopback",
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1),
            0,
        )
        assertFalse(DohFallbackDns.isGlobalPublicIpv6(mappedLoopback))

        // ::ffff:10.0.0.1
        val mappedPrivate = Inet6Address.getByAddress(
            "mapped-private",
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 10, 0, 0, 1),
            0,
        )
        assertFalse(DohFallbackDns.isGlobalPublicIpv6(mappedPrivate))

        // NAT64 well-known 64:ff9b::10.0.0.1 -> blocked
        val nat64Private = Inet6Address.getByAddress(
            "nat64-private",
            byteArrayOf(0x00, 0x64, -1, 0x9B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1),
            0,
        )
        assertFalse(DohFallbackDns.isGlobalPublicIpv6(nat64Private))

        // NAT64 well-known 64:ff9b::1.1.1.1 -> public allowed
        val nat64Public = Inet6Address.getByAddress(
            "nat64-public",
            byteArrayOf(0x00, 0x64, -1, 0x9B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1),
            0,
        )
        assertTrue(DohFallbackDns.isGlobalPublicIpv6(nat64Public))

        // Discard prefix 100::1 -> blocked
        val discard = Inet6Address.getByAddress(
            "discard",
            byteArrayOf(0x01, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
            0,
        )
        assertFalse(DohFallbackDns.isGlobalPublicIpv6(discard))
    }

    @Test
    fun memoryCookieJar_isolatesCookiesAcrossOriginsAndDropsExpired() {
        val jar = MemoryCookieJar()
        val origin = "https://animeunity.cx/".toHttpUrl()
        val activeCookie = Cookie.Builder()
            .name("animeunity_session")
            .value("secret123")
            .domain("animeunity.cx")
            .path("/")
            .expiresAt(System.currentTimeMillis() + 60_000L)
            .build()
        val expiredCookie = Cookie.Builder()
            .name("expired_tok")
            .value("old")
            .domain("animeunity.cx")
            .path("/")
            .expiresAt(System.currentTimeMillis() - 1_000L)
            .build()

        jar.saveFromResponse(origin, listOf(activeCookie, expiredCookie))

        val forOrigin = jar.loadForRequest("https://animeunity.cx/api/calendar".toHttpUrl())
        assertEquals(listOf("animeunity_session"), forOrigin.map { it.name })

        val forThirdParty = jar.loadForRequest("https://vixcloud.cc/embed/1".toHttpUrl())
        assertTrue(forThirdParty.isEmpty())
    }

    @Test
    fun historyRepository_mergeImportPreservesOlderEpisodesWhenImportingOlderBackup() = runTest {
        val tempDir = Files.createTempDirectory("aw_history_merge_test").toFile()
        try {
            val repo = HistoryRepository(tempDir)
            val anime = AnimeItem(id = "7431", title = "Steel Ball Run", malId = 12345)

            val currentEntry = WatchHistoryEntry(
                anime = anime,
                currentEpisodeNum = "5",
                currentVideoId = "v5",
                progressMs = 120_000L,
                durationMs = 1_440_000L,
                completed = false,
                updatedAt = 2_000L,
                episodesProgress = mapOf(
                    "5" to EpisodeProgress("5", "v5", 120_000L, 1_440_000L, completed = false, updatedAt = 2_000L),
                ),
            )
            val olderBackupEntry = WatchHistoryEntry(
                anime = anime.copy(plot = "Corsa a cavallo attraverso l'America"),
                currentEpisodeNum = "2",
                currentVideoId = "v2",
                progressMs = 0L,
                durationMs = 1_440_000L,
                completed = true,
                updatedAt = 1_000L,
                episodesProgress = mapOf(
                    "1" to EpisodeProgress("1", "v1", 0L, 1_440_000L, completed = true, updatedAt = 900L),
                    "2" to EpisodeProgress("2", "v2", 0L, 1_440_000L, completed = true, updatedAt = 1_000L),
                ),
            )

            repo.mergeImport(listOf(currentEntry))
            repo.mergeImport(listOf(olderBackupEntry))
            repo.flush()

            val merged = repo.getEntry(anime.uniqueKey)
            assertNotNull(merged)
            assertEquals("5", merged!!.currentEpisodeNum)
            assertEquals(2_000L, merged.updatedAt)
            assertEquals("Corsa a cavallo attraverso l'America", merged.anime.plot)
            assertEquals(setOf("1", "2", "5"), merged.episodesProgress.keys)
            assertTrue(merged.episodesProgress["1"]!!.completed)
            assertTrue(merged.episodesProgress["2"]!!.completed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun runCatchingCancellable_rethrowsCancellationAndCatchesRegularErrors() {
        val regularFailure = runCatchingCancellable { throw IllegalStateException("boom") }
        assertTrue(regularFailure.isFailure)

        try {
            runCatchingCancellable { throw CancellationException("cancelled") }
            fail("Expected CancellationException to propagate")
        } catch (_: CancellationException) {
            // Expected
        }
    }

    @Test
    fun formattingUtilities_areLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALY)
            assertEquals("02:05", formatTimeMs(125_000L))
            assertEquals("1:01:01", formatTimeMs(3_661_000L))
            assertEquals("1.5 MB", formatBytes((1.5 * 1024 * 1024).toLong()))
            assertEquals("1.50 GB", formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun calendarParser_parsesWeeklyCalendarGroupsAndEntriesCorrectly() {
        val payload = Json.parseToJsonElement(
            """
            {
              "Venerdì - Oggi": [
                {
                  "anime": {
                    "id": 7808,
                    "slug": "chainsaw-man-2",
                    "title": "Chainsaw Man Season 2",
                    "type": "tv",
                    "images": { "poster": "https://cdn.example.com/p.jpg" },
                    "variants": [{ "language": "jpn", "status": "ongoing", "episodes_count": 10 }]
                  },
                  "isDub": false,
                  "airedEpisodes": [{ "number": "10", "videos": [{ "id": 112459, "language": "jpn" }] }],
                  "airingDay": "friday",
                  "releaseTime": "2026-11-27T18:30:00+01:00",
                  "nextEpisodeNumber": "11",
                  "isLate": false,
                  "isAired": true
                }
              ],
              "Sabato - 28/11": [
                {
                  "anime": {
                    "id": 7901,
                    "slug": "solo-leveling",
                    "title": "Solo Leveling",
                    "type": "tv",
                    "variants": [{ "language": "ita", "status": "ongoing", "episodes_count": 4 }]
                  },
                  "isDub": true,
                  "airedEpisodes": [],
                  "airingDay": "saturday",
                  "releaseTime": "2026-11-28T16:00:00+01:00",
                  "nextEpisodeNumber": "5",
                  "isLate": true,
                  "isAired": false
                }
              ]
            }
            """.trimIndent()
        ).jsonObject

        val groups = AnimeUnityParser.parseCalendarPayload(payload)
        assertEquals(2, groups.size)

        val today = groups[0]
        assertTrue(today.isToday)
        assertEquals("Oggi", today.shortLabel)
        assertEquals(1, today.entries.size)
        assertEquals("Chainsaw Man Season 2", today.entries[0].anime.title)
        assertEquals("10", today.entries[0].airedEpisodeNumber)
        assertEquals("11", today.entries[0].nextEpisodeNumber)
        assertTrue(today.entries[0].isAired)
        assertFalse(today.entries[0].isDub)
        assertNotNull(today.entries[0].releaseTimeLabel)

        val saturday = groups[1]
        assertFalse(saturday.isToday)
        assertEquals("Sab 28/11", saturday.shortLabel)
        assertTrue(saturday.entries[0].isDub)
        assertTrue(saturday.entries[0].isLate)
        assertEquals("Solo Leveling (ITA)", saturday.entries[0].anime.title)
    }

    @Test
    fun favoriteEpisodeUpdateDetector_seedsBaselineSilentlyAndAlertsOnlyOnNewEpisodes() {
        val fav1 = AnimeItem(id = "100", title = "Frieren", isDub = false, releasedEpisodes = 12)
        val fav2 = AnimeItem(id = "200", title = "Dandadan (ITA)", isDub = true, releasedEpisodes = 5)
        val favorites = listOf(fav1, fav2)

        // First run: empty baseline -> seeds silently with zero alerts
        val (firstAlerts, baselineAfterFirst) = FavoriteEpisodeUpdateDetector.detectNewEpisodes(
            favorites = favorites,
            storedBaseline = emptyMap(),
            freshSnapshots = mapOf(
                fav1.uniqueKey to EpisodeSnapshot(releasedCount = 12, latestEpisodeLabel = "12"),
                fav2.uniqueKey to EpisodeSnapshot(releasedCount = 5, latestEpisodeLabel = "5"),
            ),
        )
        assertTrue(firstAlerts.isEmpty())
        assertEquals(mapOf(fav1.uniqueKey to 12, fav2.uniqueKey to 5), baselineAfterFirst)

        // Second run: fav1 gets Ep. 13, fav2 unchanged -> emits exactly 1 alert for fav1
        val (secondAlerts, baselineAfterSecond) = FavoriteEpisodeUpdateDetector.detectNewEpisodes(
            favorites = favorites,
            storedBaseline = baselineAfterFirst,
            freshSnapshots = mapOf(
                fav1.uniqueKey to EpisodeSnapshot(releasedCount = 13, latestEpisodeLabel = "13"),
                fav2.uniqueKey to EpisodeSnapshot(releasedCount = 5, latestEpisodeLabel = "5"),
            ),
        )
        assertEquals(1, secondAlerts.size)
        assertEquals("100", secondAlerts[0].anime.id)
        assertEquals(12, secondAlerts[0].previousCount)
        assertEquals(13, secondAlerts[0].newCount)
        assertEquals("13", secondAlerts[0].episodeLabel)
        assertEquals(13, baselineAfterSecond[fav1.uniqueKey])

        // Third run: same counts -> zero alerts, and removing fav2 prunes its baseline
        val (thirdAlerts, baselineAfterPrune) = FavoriteEpisodeUpdateDetector.detectNewEpisodes(
            favorites = listOf(fav1),
            storedBaseline = baselineAfterSecond,
            freshSnapshots = mapOf(
                fav1.uniqueKey to EpisodeSnapshot(releasedCount = 13, latestEpisodeLabel = "13"),
            ),
        )
        assertTrue(thirdAlerts.isEmpty())
        assertEquals(setOf(fav1.uniqueKey), baselineAfterPrune.keys)
    }

    @Test
    fun suiteTvIntegration_cleansTitlesExtractsSeasonsBuildsUrlAndExportsTraktHistory() {
        assertEquals("Jujutsu Kaisen", SuiteTvIntegration.cleanTitleForSuiteTv("Jujutsu Kaisen Season 2 (ITA)"))
        assertEquals("Chainsaw Man", SuiteTvIntegration.cleanTitleForSuiteTv("Chainsaw Man (SUB ITA)"))
        assertEquals(2, SuiteTvIntegration.extractSeasonNumber("Jujutsu Kaisen Season 2 (ITA)"))
        assertEquals(3, SuiteTvIntegration.extractSeasonNumber("Kimetsu no Yaiba Stagione 3"))
        assertEquals(1, SuiteTvIntegration.extractSeasonNumber("Chainsaw Man"))

        val searchUrl = SuiteTvIntegration.buildSearchUrl("Jujutsu Kaisen Season 2 (ITA)")
        assertEquals("https://www.suitetv.app/it/search?q=Jujutsu+Kaisen", searchUrl)

        val anime = AnimeItem(id = "55", slug = "jujutsu-kaisen-2", title = "Jujutsu Kaisen Season 2 (ITA)", year = 2023, isDub = true)
        val history = listOf(
            WatchHistoryEntry(
                anime = anime,
                currentEpisodeNum = "3",
                currentVideoId = "v3",
                completed = false,
                updatedAt = 1_700_000_000_000L,
                episodesProgress = mapOf(
                    "1" to EpisodeProgress("1", "v1", 0L, 1_440_000L, completed = true, updatedAt = 1_700_000_000_000L),
                    "2" to EpisodeProgress("2", "v2", 0L, 1_440_000L, completed = true, updatedAt = 1_700_000_060_000L),
                    "3" to EpisodeProgress("3", "v3", 300_000L, 1_440_000L, completed = false, updatedAt = 1_700_000_120_000L),
                ),
            )
        )

        val export = SuiteTvIntegration.buildTraktHistoryExport(history)
        assertEquals(1, export.showCount)
        assertEquals(2, export.episodeCount)

        val parsed = Json.parseToJsonElement(export.jsonPayload).jsonArray
        assertEquals(2, parsed.size)
        val first = parsed[0].jsonObject
        assertEquals("watch", first["action"]?.jsonPrimitive?.content)
        assertEquals("episode", first["type"]?.jsonPrimitive?.content)
        assertEquals(2, first["episode"]?.jsonObject?.get("season")?.jsonPrimitive?.int)
        assertEquals(1, first["episode"]?.jsonObject?.get("number")?.jsonPrimitive?.int)
        assertEquals("Jujutsu Kaisen", first["show"]?.jsonObject?.get("title")?.jsonPrimitive?.content)
        assertEquals(2023, first["show"]?.jsonObject?.get("year")?.jsonPrimitive?.int)
    }

    @Test
    fun continueWatchingFilter_hidesFinishedSeasonAndReappearsWhenNewEpisodeReleases() = runTest {
        val tempDir = Files.createTempDirectory("aw_continue_watching_test").toFile()
        try {
            val repo = HistoryRepository(tempDir)
            val completedSeasonAnime = AnimeItem(
                id = "7431",
                title = "Chainsaw Man",
                totalEpisodes = 12,
                releasedEpisodes = 12,
            )

            // 1. Mid-episode 12 -> MUST show in Continue Watching
            repo.updateProgress(
                anime = completedSeasonAnime,
                episodeNum = "12",
                videoId = "v12",
                positionMs = 600_000L,
                durationMs = 1_440_000L,
                isAtLatestEpisode = true,
            )
            assertTrue(repo.getEntry(completedSeasonAnime.uniqueKey)!!.shouldShowInContinueWatching)

            // 2. Completed episode 12/12 -> MUST be hidden from Continue Watching, kept in History
            repo.updateProgress(
                anime = completedSeasonAnime,
                episodeNum = "12",
                videoId = "v12",
                positionMs = 1_400_000L,
                durationMs = 1_440_000L,
                isAtLatestEpisode = true,
            )
            val finished = repo.getEntry(completedSeasonAnime.uniqueKey)!!
            assertTrue(finished.completed)
            assertTrue(finished.isCaughtUp)
            assertFalse(finished.shouldShowInContinueWatching)

            // 3. Legacy completed entry without isAtLatestEpisode flag -> still caught-up via totalEpisodes
            val legacyFinished = WatchHistoryEntry(
                anime = AnimeItem(id = "900", title = "Frieren", totalEpisodes = 28),
                currentEpisodeNum = "28",
                currentVideoId = "v28",
                completed = true,
                isAtLatestEpisode = false,
            )
            assertFalse(legacyFinished.shouldShowInContinueWatching)

            // 4. Ongoing anime finishes Ep. 10/10 -> hidden, then Ep. 11 releases -> reappears!
            val ongoingAnime = AnimeItem(id = "800", title = "Solo Leveling", releasedEpisodes = 10)
            repo.updateProgress(
                anime = ongoingAnime,
                episodeNum = "10",
                videoId = "v10",
                positionMs = 1_420_000L,
                durationMs = 1_440_000L,
                isAtLatestEpisode = true,
            )
            assertFalse(repo.getEntry(ongoingAnime.uniqueKey)!!.shouldShowInContinueWatching)

            repo.syncKnownEpisodeAvailability(ongoingAnime.uniqueKey, availableEpisodes = 11, lastEpisodeNum = "11")
            repo.flush()
            val afterNewDrop = repo.getEntry(ongoingAnime.uniqueKey)!!
            assertTrue(afterNewDrop.shouldShowInContinueWatching)
            assertEquals(11, afterNewDrop.anime.releasedEpisodes)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun animeUnityDeepLinkParser_parsesSupportedLinksAndBlocksUntrustedDomains() {
        val detail = AnimeUnityDeepLinkParser.parse("https://animeunity.cx/anime/7431-steel-ball-run")
        assertEquals(AnimeUnityDeepLink.AnimeDetail("7431", "steel-ball-run", isDubHint = false), detail)

        val dubDetail = AnimeUnityDeepLinkParser.parse("https://www.animeunity.so/anime/7431-steel-ball-run-ita")
        assertEquals(AnimeUnityDeepLink.AnimeDetail("7431", "steel-ball-run-ita", isDubHint = true), dubDetail)

        val episode = AnimeUnityDeepLinkParser.parse("https://animeunity.cx/anime/7431-steel-ball-run/112459")
        assertEquals(AnimeUnityDeepLink.EpisodePlayback("7431", "steel-ball-run", "112459", isDubHint = false), episode)

        assertEquals(AnimeUnityDeepLink.Calendar, AnimeUnityDeepLinkParser.parse("https://animeunity.cx/calendario"))
        assertEquals(AnimeUnityDeepLink.Search("chainsaw man"), AnimeUnityDeepLinkParser.parse("https://animeunity.cx/archivio?title=chainsaw+man"))
        assertEquals(AnimeUnityDeepLink.Home(trending = true), AnimeUnityDeepLinkParser.parse("https://animeunity.cx/top-anime"))
        assertEquals(AnimeUnityDeepLink.Home(trending = false), AnimeUnityDeepLinkParser.parse("http://animeunity.to/"))

        // Untrusted phishing hosts and non-numeric anime paths must be rejected
        assertEquals(null, AnimeUnityDeepLinkParser.parse("https://animeunity.evil.com/anime/7431-test"))
        assertEquals(null, AnimeUnityDeepLinkParser.parse("https://animeunity.cx/anime/not-numeric"))
        assertEquals(null, AnimeUnityDeepLinkParser.parse("javascript:alert(1)"))
    }
}
