package com.carloj005.awanime

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.EpisodeProgress
import com.carloj005.awanime.data.model.ThemeMode
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.formatBytes
import com.carloj005.awanime.data.model.formatTimeMs
import com.carloj005.awanime.data.model.resolveNextEpisodeIndex
import com.carloj005.awanime.data.network.DohFallbackDns
import com.carloj005.awanime.data.network.MemoryCookieJar
import com.carloj005.awanime.data.network.SkipDatabaseClient
import com.carloj005.awanime.data.network.VixCloudExtractor
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.storage.JsonFileStore
import com.carloj005.awanime.data.update.AppUpdater
import com.carloj005.awanime.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Inet6Address
import java.util.Locale

class SecurityStorageAndRefactorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun vixCloudExtractor_rejectsHostSubstringBypassAndAcceptsValidSubdomains() {
        assertTrue(VixCloudExtractor.isTrustedEmbedUrl("https://vixcloud.cc/embed/307688"))
        assertTrue(VixCloudExtractor.isTrustedEmbedUrl("https://au-u1-01.vixcloud.cc/embed/307688?canPlayFHD=1"))
        assertTrue(VixCloudExtractor.isTrustedEmbedUrl("https://au-d1-01.vix-content.net/download/1.mp4"))
        assertTrue(VixCloudExtractor.isTrustedEmbedUrl("https://vixsrc.to/embed/12"))
        assertTrue(VixCloudExtractor.isTrustedEmbedUrl("https://www.animeunity.cx/embed/12"))

        // Substring / lookalike host bypasses must be rejected
        assertFalse(VixCloudExtractor.isTrustedEmbedUrl("https://vixcloud.evil.com/embed/307688"))
        assertFalse(VixCloudExtractor.isTrustedEmbedUrl("https://evil-vixcloud.org/embed/307688"))
        assertFalse(VixCloudExtractor.isTrustedEmbedUrl("https://vixcloud.cc.evil.com/embed/307688"))
        assertFalse(VixCloudExtractor.isTrustedEmbedUrl("https://vix-content.attacker.net/x"))
        assertFalse(VixCloudExtractor.isTrustedEmbedUrl("http://vixcloud.cc/embed/307688"))
        assertFalse(VixCloudExtractor.isTrustedEmbedUrl("https://127.0.0.1/embed/1"))
    }

    @Test
    fun vixCloudExtractor_extractsMasterPlaylistDirectUrlAndBestHlsVariant() {
        val embedHtml = """
            <script>
            window.canPlayFHD = true;
            window.downloadUrl = 'https://au-d1-01.vix-content.net/download/ep1.mp4?token=abc';
            window.masterPlaylist = {
              params: {
                'token': 'tok123',
                'expires': '1999999999',
                'asn': '12345'
              },
              url: 'https://vixcloud.cc/playlist/801828'
            };
            </script>
        """.trimIndent()

        val masterUrl = VixCloudExtractor.extractHlsMasterUrl(embedHtml)
        assertNotNull(masterUrl)
        val parsed = masterUrl!!.toHttpUrl()
        assertEquals("tok123", parsed.queryParameter("token"))
        assertEquals("1999999999", parsed.queryParameter("expires"))
        assertEquals("12345", parsed.queryParameter("asn"))
        assertEquals("1", parsed.queryParameter("h"))

        assertEquals(
            "https://au-d1-01.vix-content.net/download/ep1.mp4?token=abc",
            VixCloudExtractor.extractDirectDownloadUrl(embedHtml),
        )
        assertNull(VixCloudExtractor.extractDirectDownloadUrl("window.downloadUrl = 'http://insecure.example/a.mp4'"))

        val m3u8 = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=854x480
            /playlist/801828?type=video&rendition=480p
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            /playlist/801828?type=video&rendition=1080p
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            /playlist/801828?type=video&rendition=720p
        """.trimIndent()
        val best = VixCloudExtractor.parseBestHlsVariant(m3u8, "https://vixcloud.cc/playlist/801828".toHttpUrl())
        assertNotNull(best)
        assertEquals("https://vixcloud.cc/playlist/801828?type=video&rendition=1080p", best!!.first)
        assertEquals("1080p FHD", best.second)
    }

    @Test
    fun dohFallbackDns_enforcesStrictTargetsAndBlocksIpv6MappedAndNat64PrivateAddresses() {
        assertTrue(DohFallbackDns.isTargetHostname("animeunity.cx"))
        assertTrue(DohFallbackDns.isTargetHostname("au-u1-01.vixcloud.cc"))
        assertFalse(DohFallbackDns.isTargetHostname("animeunity.evil.com"))
        assertFalse(DohFallbackDns.isTargetHostname("evil-vixcloud.org"))
        assertFalse(DohFallbackDns.isTargetHostname("google.com"))

        // ::ffff:127.0.0.1 as raw Inet6Address
        val mappedLoopback = Inet6Address.getByAddress(
            "mapped-loopback",
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1),
            0,
        )
        assertFalse(DohFallbackDns.isGlobalPublicIpv6(mappedLoopback))

        // ::ffff:10.0.0.1 as raw Inet6Address
        val mappedPrivate = Inet6Address.getByAddress(
            "mapped-private",
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 10, 0, 0, 1),
            0,
        )
        assertFalse(DohFallbackDns.isGlobalPublicIpv6(mappedPrivate))

        // NAT64 well-known prefix 64:ff9b::10.0.0.1 -> private IPv4 must be blocked
        val nat64Private = Inet6Address.getByAddress(
            "nat64-private",
            byteArrayOf(0x00, 0x64, -1, 0x9B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1),
            0,
        )
        assertFalse(DohFallbackDns.isGlobalPublicIpv6(nat64Private))

        // NAT64 well-known prefix 64:ff9b::1.1.1.1 -> public IPv4 is allowed
        val nat64Public = Inet6Address.getByAddress(
            "nat64-public",
            byteArrayOf(0x00, 0x64, -1, 0x9B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1),
            0,
        )
        assertTrue(DohFallbackDns.isGlobalPublicIpv6(nat64Public))

        // Discard prefix 100::1
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

        val loadedOrigin = jar.loadForRequest("https://animeunity.cx/api/archive".toHttpUrl())
        assertEquals(1, loadedOrigin.size)
        assertEquals("animeunity_session", loadedOrigin.first().name)

        val loadedThirdParty = jar.loadForRequest("https://vixcloud.cc/embed/1".toHttpUrl())
        assertTrue(loadedThirdParty.isEmpty())

        val loadedLookalike = jar.loadForRequest("https://evilanimeunity.cx/".toHttpUrl())
        assertTrue(loadedLookalike.isEmpty())
    }

    @Test
    fun appUpdater_strictTrustedGitHubHostsRejectsArbitraryUserContentSubdomains() {
        assertTrue(AppUpdater.isTrustedGitHubHost("api.github.com"))
        assertTrue(AppUpdater.isTrustedGitHubHost("github.com"))
        assertTrue(AppUpdater.isTrustedGitHubHost("objects.githubusercontent.com"))
        assertTrue(AppUpdater.isTrustedGitHubHost("release-assets.githubusercontent.com"))

        assertFalse(AppUpdater.isTrustedGitHubHost("raw.githubusercontent.com"))
        assertFalse(AppUpdater.isTrustedGitHubHost("gist.githubusercontent.com"))
        assertFalse(AppUpdater.isTrustedGitHubHost("evil.githubusercontent.com"))
    }

    @Test
    fun skipDatabaseClient_buildsValidParameterizedGraphqlPayloads() {
        val trickyAnime = AnimeItem(
            id = "42",
            title = "Attack \"On\" \\ Titan\nPart 2 (ITA)",
            anilistId = 0,
        )
        val rawBody = SkipDatabaseClient.buildAnimeSkipRequestBody(trickyAnime)
        assertNotNull(rawBody)
        val parsed = Json.parseToJsonElement(rawBody!!).jsonObject
        assertTrue(parsed["query"]!!.jsonPrimitive.content.contains("\$search"))
        val searchVar = parsed["variables"]!!.jsonObject["search"]!!.jsonPrimitive.content
        assertEquals("Attack \"On\" \\ Titan Part 2", searchVar)

        val anilistAnime = AnimeItem(id = "99", title = "Frieren", anilistId = 154587)
        val anilistBody = SkipDatabaseClient.buildAnimeSkipRequestBody(anilistAnime)!!
        val parsedAnilist = Json.parseToJsonElement(anilistBody).jsonObject
        assertEquals("154587", parsedAnilist["variables"]!!.jsonObject["serviceId"]!!.jsonPrimitive.content)
    }

    @Test
    fun jsonFileStoreAndHistoryMergeImport_roundTripsAndPreservesOlderEpisodes() {
        val settingsFile = File(tempFolder.root, "settings.json")
        val settingsStore = JsonFileStore(settingsFile, AppSettings.serializer())
        val expected = AppSettings(themeMode = ThemeMode.DARK, wifiOnlyDownloads = true, autoSkipIntro = true)
        settingsStore.writeBlocking(expected)
        assertEquals(expected, settingsStore.readBlocking(AppSettings()))

        // Corrupt JSON falls back cleanly to default value
        settingsFile.writeText("{corrupt json")
        assertEquals(AppSettings(), settingsStore.readBlocking(AppSettings()))

        val historyFile = File(tempFolder.root, "history.json")
        val historyStore = JsonFileStore(historyFile, ListSerializer(WatchHistoryEntry.serializer()))
        val repo = HistoryRepository(historyStore, loadSynchronously = true)
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

        val merged = repo.getEntry(anime.uniqueKey)
        assertNotNull(merged)
        // Newer top-level state wins
        assertEquals("5", merged!!.currentEpisodeNum)
        assertEquals(2_000L, merged.updatedAt)
        // Rich metadata from older entry fills blank plot
        assertEquals("Corsa a cavallo attraverso l'America", merged.anime.plot)
        // Older watched episodes (1 and 2) MUST be preserved alongside episode 5
        assertEquals(setOf("1", "2", "5"), merged.episodesProgress.keys)
        assertTrue(merged.episodesProgress["1"]!!.completed)
        assertTrue(merged.episodesProgress["2"]!!.completed)
    }

    @Test
    fun resolveNextEpisodeIndex_advancesOnlyWhenCompletedAndNextExists() {
        val episodes = listOf(
            EpisodeItem(number = "1", videoId = "v1"),
            EpisodeItem(number = "2", videoId = "v2"),
            EpisodeItem(number = "3", videoId = "v3"),
        )
        val anime = AnimeItem(id = "1", title = "Test")

        assertEquals(0, resolveNextEpisodeIndex(episodes, null))
        assertEquals(0, resolveNextEpisodeIndex(emptyList(), null))

        val inProgress = WatchHistoryEntry(anime, currentEpisodeNum = "2", currentVideoId = "v2", completed = false)
        assertEquals(1, resolveNextEpisodeIndex(episodes, inProgress))

        val completedMid = WatchHistoryEntry(anime, currentEpisodeNum = "2", currentVideoId = "v2", completed = true)
        assertEquals(2, resolveNextEpisodeIndex(episodes, completedMid))

        val completedLast = WatchHistoryEntry(anime, currentEpisodeNum = "3", currentVideoId = "v3", completed = true)
        assertEquals(2, resolveNextEpisodeIndex(episodes, completedLast))

        val missing = WatchHistoryEntry(anime, currentEpisodeNum = "99", currentVideoId = "v99", completed = true)
        assertEquals(0, resolveNextEpisodeIndex(episodes, missing))
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
}
