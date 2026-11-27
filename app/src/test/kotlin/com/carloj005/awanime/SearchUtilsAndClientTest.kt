package com.carloj005.awanime

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AnimeStatus
import com.carloj005.awanime.data.model.AppBackupData
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.StreamCandidate
import com.carloj005.awanime.data.model.ThemeMode
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.model.resolveResumeTarget
import com.carloj005.awanime.data.network.AnimeSkipMarker
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.network.DohFallbackDns
import com.carloj005.awanime.data.network.RawSkipCandidate
import com.carloj005.awanime.data.network.SearchUtils
import com.carloj005.awanime.data.network.SkipTimeRemoteDataSource
import com.carloj005.awanime.data.network.SkipTimeValidator
import com.carloj005.awanime.data.network.UrlSecurityPolicy
import com.carloj005.awanime.data.storage.HistoryRepository
import com.carloj005.awanime.data.storage.JsonFileStore
import com.carloj005.awanime.data.update.AppUpdater
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.file.Files

class SearchUtilsAndClientTest {

    @Test
    fun sortAnimeItems_ordersJoJoChronologicallyWithSubBeforeIta() {
        val scrambled = listOf(
            AnimeItem(id = "627", title = "Le Bizzarre Avventure di JoJo: Diamond is Unbreakable", year = 2016, season = "Primavera"),
            AnimeItem(id = "7431", title = "Le Bizzarre Avventure di JoJo: Steel Ball Run (ITA)", year = 2026, season = "Inverno", isDub = true),
            AnimeItem(id = "7431", title = "Le Bizzarre Avventure di JoJo: Steel Ball Run", year = 2026, season = "Inverno", isDub = false),
            AnimeItem(id = "2625", title = "Le Bizzarre Avventure di JoJo: Vento Aureo", year = 2018, season = "Autunno"),
            AnimeItem(id = "2624", title = "Le Bizzarre Avventure di JoJo", year = 2012, season = "Autunno"),
            AnimeItem(id = "628", title = "Le Bizzarre Avventure di JoJo: Stardust Crusaders", year = 2014, season = "Primavera"),
        )

        val sorted = SearchUtils.sortAnimeItems(scrambled, "jojo")
        assertEquals(
            listOf(
                "Le Bizzarre Avventure di JoJo",
                "Le Bizzarre Avventure di JoJo: Stardust Crusaders",
                "Le Bizzarre Avventure di JoJo: Diamond is Unbreakable",
                "Le Bizzarre Avventure di JoJo: Vento Aureo",
                "Le Bizzarre Avventure di JoJo: Steel Ball Run",
                "Le Bizzarre Avventure di JoJo: Steel Ball Run (ITA)",
            ),
            sorted.map { it.title },
        )
    }

    @Test
    fun fuzzyFallback_matchesTypoJojoStealBallRun() {
        val candidates = listOf(
            AnimeItem(id = "628", title = "Le Bizzarre Avventure di JoJo: Stardust Crusaders"),
            AnimeItem(id = "7431", title = "Le Bizzarre Avventure di JoJo: Steel Ball Run", titleJa = "Steel Ball Run: JoJo no Kimyou na Bouken"),
            AnimeItem(id = "7431", title = "Le Bizzarre Avventure di JoJo: Steel Ball Run (ITA)", isDub = true),
            AnimeItem(id = "5254", title = "Dragon Ball Z"),
        )
        val ranked = SearchUtils.rankFuzzyCandidates("jojo steal ball run", candidates)
        assertEquals(2, ranked.size)
        assertTrue(ranked.all { "Steel Ball Run" in it.title })
    }

    @Test
    fun episodeSortKey_handlesRangesDecimalsAndSpecialsWithoutCrashing() {
        val raw = listOf("OVA", "12", "5.5", "3-4", "1", "7b", "7a", "SP1", "7")
        val sorted = raw.sortedWith(compareBy { SearchUtils.episodeSortKey(it) })
        assertEquals(listOf("1", "3-4", "5.5", "7", "7a", "7b", "12", "SP1", "OVA"), sorted)
    }

    @Test
    fun animeUnityClient_expandsSubAndItaVariantsWithMalIdAndTypedStatus() {
        val sample = Json.parseToJsonElement(
            """
            {
              "id": 7771,
              "slug": "black-clover-2",
              "title": "Black Clover 2",
              "type": "tv",
              "season": "fall",
              "year": 2026,
              "mal_id": 61967,
              "default_status": "ongoing",
              "variants": [
                {"language": "ita", "status": "completed", "episodes_count": 5},
                {"language": "jpn", "status": "ongoing", "episodes_count": 8}
              ]
            }
            """.trimIndent()
        ).jsonObject

        val client = AnimeUnityClient(context = null)
        val expanded = client.parseAnimeVariants(sample)
        assertEquals(2, expanded.size)
        assertEquals("Black Clover 2", expanded[0].title)
        assertEquals(61967, expanded[0].malId)
        assertFalse(expanded[0].isDub)
        assertEquals(8, expanded[0].releasedEpisodes)
        assertEquals(AnimeStatus.ONGOING, expanded[0].animeStatus)

        assertEquals("Black Clover 2 (ITA)", expanded[1].title)
        assertTrue(expanded[1].isDub)
        assertEquals(5, expanded[1].releasedEpisodes)
        assertEquals(AnimeStatus.COMPLETED, expanded[1].animeStatus)
    }

    @Test
    fun extractVixCloudTimestamps_parsesRealIntroAndOutroSegments() {
        val embedHtml = """
            <script>
            window.video = { id: 307688 };
            timestamps: [{"kind":"intro","start_time":59.907482993197,"end_time":147.9074829932,"source":"detected","confidence":156.8125},{"kind":"outro","start_time":1336.029,"end_time":1420.029,"source":"detected","confidence":99.1}]
            </script>
        """.trimIndent()

        val client = AnimeUnityClient(context = null)
        val segments = client.extractVixCloudTimestamps(embedHtml)
        assertEquals(2, segments.size)
        assertEquals(SkipKind.INTRO, segments[0].kind)
        assertEquals(59_907L, segments[0].startMs)
        assertEquals(147_907L, segments[0].endMs)
        assertTrue(segments[0].contains(90_000L))
        assertFalse(segments[0].contains(20_000L))

        assertEquals(SkipKind.OUTRO, segments[1].kind)
        assertEquals(1_336_029L, segments[1].startMs)
        assertTrue(segments[1].contains(1_380_000L))
    }

    @Test
    fun appUpdater_validatesVersionsTrustedHostsAndFailsClosedOnSha256AndSigners() {
        assertTrue(AppUpdater.isNewerVersion("v1.2.0", "1.1.0"))
        assertTrue(AppUpdater.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(AppUpdater.isNewerVersion("1.1.1", "1.1.0"))
        assertFalse(AppUpdater.isNewerVersion("1.1.0", "1.1.0"))
        assertFalse(AppUpdater.isNewerVersion("v1.0.9", "1.1.0"))

        assertTrue(AppUpdater.isTrustedGitHubDownloadUrl("https://github.com/Carloj005/aw-android/releases/download/v1.2.0/AW-Anime-v1.2.0.apk"))
        assertTrue(AppUpdater.isTrustedGitHubDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertFalse(AppUpdater.isTrustedGitHubDownloadUrl("http://github.com/Carloj005/aw-android/releases/download/v1.2.0/AW-Anime-v1.2.0.apk"))
        assertFalse(AppUpdater.isTrustedGitHubDownloadUrl("https://evil-github.com/fake.apk"))

        val validHash = "997a6e9d51a460c1af04d4288ace4e77c5d753fada676d0120463c12039f41a5"
        val otherHash = "0000000000000000000000000000000000000000000000000000000000000000"
        assertEquals(validHash, AppUpdater.parseSha256Line("$validHash  AW-Anime-v1.2.0.apk\n"))
        assertNull(AppUpdater.parseSha256Line("not-a-valid-sha256-hash"))

        // Fail-closed SHA-256 verification
        AppUpdater.verifySha256OrThrow(actualHex = validHash, expectedHex = validHash)
        assertThrows(SecurityException::class.java) {
            AppUpdater.verifySha256OrThrow(actualHex = validHash, expectedHex = null)
        }
        assertThrows(SecurityException::class.java) {
            AppUpdater.verifySha256OrThrow(actualHex = validHash, expectedHex = otherHash)
        }

        // Fail-closed APK signer verification
        AppUpdater.verifySignerDigestsOrThrow(setOf(validHash), setOf(validHash))
        assertThrows(SecurityException::class.java) {
            AppUpdater.verifySignerDigestsOrThrow(emptySet(), setOf(validHash))
        }
        assertThrows(SecurityException::class.java) {
            AppUpdater.verifySignerDigestsOrThrow(setOf(validHash), emptySet())
        }
        assertThrows(SecurityException::class.java) {
            AppUpdater.verifySignerDigestsOrThrow(setOf(validHash), setOf(otherHash))
        }
    }

    @Test
    fun antiPhishingHostAllowlistsAndDohIpv4Ipv6Validation_rejectSubstringBypassAndPrivateTargets() {
        assertFalse(AnimeUnityClient.isValidAnimeUnityHtml("<html><body>Fake Clone</body></html>"))
        assertTrue(
            AnimeUnityClient.isValidAnimeUnityHtml(
                """<div id="app" data-page="{&quot;props&quot;:{&quot;scws_url&quot;:&quot;https://vixcloud.cc&quot;}}"></div>"""
            )
        )

        assertTrue(AnimeUnityClient.isTrustedAnimeUnityOrigin("https://animeunity.cx"))
        assertTrue(AnimeUnityClient.isTrustedAnimeUnityOrigin("https://www.animeunity.so"))
        assertFalse(AnimeUnityClient.isTrustedAnimeUnityOrigin("http://animeunity.cx"))
        assertFalse(AnimeUnityClient.isTrustedAnimeUnityOrigin("https://animeunity.cx.evil.com"))

        // Strict anchored embed allowlist (blocks vixcloud.evil.com and attacker-vixsrc.com)
        assertTrue(AnimeUnityClient.isTrustedEmbedUrl("https://vixcloud.cc/embed/307688?canPlayFHD=1"))
        assertTrue(AnimeUnityClient.isTrustedEmbedUrl("https://au-u1-01.vix-content.net/playlist/307688"))
        assertFalse(AnimeUnityClient.isTrustedEmbedUrl("http://vixcloud.cc/embed/307688"))
        assertFalse(AnimeUnityClient.isTrustedEmbedUrl("https://127.0.0.1/embed/1"))
        assertFalse(AnimeUnityClient.isTrustedEmbedUrl("https://vixcloud.evil.com/embed/307688"))
        assertFalse(AnimeUnityClient.isTrustedEmbedUrl("https://attacker-vixsrc.com/embed/307688"))

        assertFalse(UrlSecurityPolicy.isDohTargetHost("vixcloud.evil.com"))
        assertTrue(UrlSecurityPolicy.isDohTargetHost("animeunity.cx"))
        assertFalse(UrlSecurityPolicy.isTrustedMediaUrl("https://127.0.0.1/video.mp4"))
        assertFalse(UrlSecurityPolicy.isTrustedMediaUrl("https://192.168.1.20/video.mp4"))
        assertFalse(UrlSecurityPolicy.isTrustedMediaUrl("http://au-d1-01.vix-content.net/video.mp4"))
        assertTrue(UrlSecurityPolicy.isTrustedMediaUrl("https://au-d1-01.vix-content.net/video.mp4"))

        assertFalse(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("127.0.0.1") as Inet4Address))
        assertFalse(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("169.254.169.254") as Inet4Address))
        assertFalse(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("192.168.1.1") as Inet4Address))
        assertFalse(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("100.64.0.1") as Inet4Address))
        assertTrue(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("104.21.55.12") as Inet4Address))

        assertFalse(DohFallbackDns.isGlobalPublicAddress(InetAddress.getByName("::1")))
        assertFalse(DohFallbackDns.isGlobalPublicAddress(InetAddress.getByName("fe80::1")))
        assertFalse(DohFallbackDns.isGlobalPublicAddress(InetAddress.getByName("fd12:3456:789a::1")))
        assertTrue(DohFallbackDns.isGlobalPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun backupData_roundTripsSettingsAndFavoritesCleanly() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = AppBackupData(
            settings = AppSettings(
                themeMode = ThemeMode.DARK,
                wifiOnlyDownloads = true,
                autoPipEnabled = true,
                recentQueries = listOf("jojo", "solo leveling"),
            ),
            favorites = listOf(AnimeItem(id = "7431", title = "Steel Ball Run", malId = 12345)),
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<AppBackupData>(encoded)
        assertEquals(ThemeMode.DARK, decoded.settings.themeMode)
        assertTrue(decoded.settings.wifiOnlyDownloads)
        assertEquals(listOf("jojo", "solo leveling"), decoded.settings.recentQueries)
        assertEquals(1, decoded.favorites.size)
        assertEquals("Steel Ball Run", decoded.favorites.first().title)
    }

    @Test
    fun streamCandidate_explicitlyMarksExtensionlessVixCloudPlaylistAsHls() {
        val hlsCand = StreamCandidate(
            url = "https://vixcloud.cc/playlist/801828?type=video&rendition=1080p&token=abc&expires=9999",
            isHls = true,
            qualityLabel = "1080p FHD HLS",
        )
        val mp4Cand = StreamCandidate(
            url = "https://au-d1-01.vix-content.net/download/1080p.mp4?token=xyz",
            isHls = false,
            qualityLabel = "MP4",
        )
        assertTrue(hlsCand.isHls)
        assertFalse(hlsCand.url.endsWith(".m3u8"))
        assertFalse(mp4Cand.isHls)
    }

    @Test
    fun skipTimeValidator_eliminatesFakeChainsawManEp1OpAndKeepsRealEd() {
        val aniExact = listOf(
            RawSkipCandidate(SkipKind.INTRO, 363_915L, 453_915L, 1525.1, "aniskip"),
            RawSkipCandidate(SkipKind.OUTRO, 1_434_000L, 1_527_000L, 1527.0, "aniskip"),
        )
        val aniGlobal = listOf(
            RawSkipCandidate(SkipKind.INTRO, 230_719L, 320_719L, 1435.0, "aniskip"),
            RawSkipCandidate(SkipKind.OUTRO, 1_434_000L, 1_527_000L, 1527.0, "aniskip"),
        )
        val animeSkip = SkipTimeValidator.parseAnimeSkipEpisode(
            markers = listOf(
                AnimeSkipMarker(0.0, "Canon"),
                AnimeSkipMarker(87.0, "Intro"),
            ),
            baseDurationSec = 1525.1,
            realDurationSec = 1525,
        )

        val resolved = SkipTimeValidator.reconcileCrowdsourcedSegments(aniExact, aniGlobal, animeSkip, 1525)
        assertEquals(1, resolved.size)
        assertEquals(SkipKind.OUTRO, resolved[0].kind)
        assertEquals(1_434_000L, resolved[0].startMs)
        assertEquals(1_525_000L, resolved[0].endMs)
    }

    @Test
    fun skipTimeValidator_crossVerifiesValidEpisodesAndVetoesFakeEp1OpsAndEarlyEds() {
        val slAni = listOf(
            RawSkipCandidate(SkipKind.INTRO, 62_760L, 152_358L, 1420.0, "aniskip"),
            RawSkipCandidate(SkipKind.OUTRO, 1_336_493L, 1_420_000L, 1420.0, "aniskip"),
        )
        val slAnimeSkip = SkipTimeValidator.parseAnimeSkipEpisode(
            markers = listOf(
                AnimeSkipMarker(0.0, "Canon"),
                AnimeSkipMarker(1325.0, "New Credits"),
                AnimeSkipMarker(1415.0, "Preview"),
            ),
            baseDurationSec = 1419.9,
            realDurationSec = 1420,
        )
        assertTrue(slAnimeSkip.confirmsNoIntro)
        val slResolved = SkipTimeValidator.reconcileCrowdsourcedSegments(slAni, slAni, slAnimeSkip, 1420)
        assertTrue(slResolved.none { it.kind == SkipKind.INTRO })
        assertTrue(slResolved.any { it.kind == SkipKind.OUTRO && it.verified })

        val csm3Ani = listOf(
            RawSkipCandidate(SkipKind.INTRO, 276_435L, 366_435L, 1435.0, "aniskip"),
            RawSkipCandidate(SkipKind.OUTRO, 1_344_042L, 1_434_000L, 1435.0, "aniskip"),
        )
        val csm3AnimeSkip = SkipTimeValidator.parseAnimeSkipEpisode(
            markers = listOf(
                AnimeSkipMarker(0.1, "Canon"),
                AnimeSkipMarker(276.1, "Intro"),
                AnimeSkipMarker(366.0, "Canon"),
                AnimeSkipMarker(1345.0, "New Credits"),
            ),
            baseDurationSec = 1435.1,
            realDurationSec = 1435,
        )
        val csm3Resolved = SkipTimeValidator.reconcileCrowdsourcedSegments(csm3Ani, csm3Ani, csm3AnimeSkip, 1435)
        assertEquals(2, csm3Resolved.size)
        assertTrue(csm3Resolved.all { it.verified })

        val earlyFakeEd = RawSkipCandidate(SkipKind.OUTRO, 232_600L, 322_600L, 1435.0, "aniskip")
        assertFalse(SkipTimeValidator.isStructurallyValid(earlyFakeEd, 1435))

        val wrongCut = RawSkipCandidate(SkipKind.INTRO, 3_200L, 93_200L, 1559.9, "aniskip")
        assertFalse(SkipTimeValidator.isStructurallyValid(wrongCut, 1680))
    }

    @Test
    fun resolveResumeTarget_handlesCompletedLastEpisodeAndMissingEpisodeCleanly() {
        val anime = AnimeItem(id = "10", title = "Frieren")
        val episodes = listOf(
            EpisodeItem(number = "1", videoId = "v1"),
            EpisodeItem(number = "2", videoId = "v2"),
            EpisodeItem(number = "3", videoId = "v3"),
        )

        // 1. Mid-series episode completed -> advances to Episode 2
        val midCompleted = WatchHistoryEntry(
            anime = anime,
            currentEpisodeNum = "1",
            currentVideoId = "v1",
            completed = true,
        )
        val nextTarget = resolveResumeTarget(episodes, midCompleted)
        assertNotNull(nextTarget)
        assertEquals(1, nextTarget!!.episodeIndex)
        assertTrue(nextTarget.advancesToNextEpisode)
        assertFalse(nextTarget.replaysCompletedLastEpisode)
        assertEquals("Guarda Prossimo (Ep. 2)", nextTarget.detailCtaLabel())

        // 2. Final episode completed -> replays Episode 3 with honest label (not "Guarda Prossimo")
        val lastCompleted = WatchHistoryEntry(
            anime = anime,
            currentEpisodeNum = "3",
            currentVideoId = "v3",
            completed = true,
        )
        val replayTarget = resolveResumeTarget(episodes, lastCompleted)
        assertNotNull(replayTarget)
        assertEquals(2, replayTarget!!.episodeIndex)
        assertFalse(replayTarget.advancesToNextEpisode)
        assertTrue(replayTarget.replaysCompletedLastEpisode)
        assertEquals("Riguarda Ep. 3", replayTarget.detailCtaLabel())

        // 3. Unmatched episode number -> falls back to Episode 1 at 00:00 without leaking old timestamp
        val missingEpHistory = WatchHistoryEntry(
            anime = anime,
            currentEpisodeNum = "OVA1",
            currentVideoId = "v99",
            progressMs = 420_000L,
            durationMs = 1_400_000L,
            completed = false,
        )
        val fallbackTarget = resolveResumeTarget(episodes, missingEpHistory)
        assertNotNull(fallbackTarget)
        assertEquals(0, fallbackTarget!!.episodeIndex)
        assertEquals(0L, fallbackTarget.startPositionMs)
        assertEquals("Guarda Episodio 1", fallbackTarget.detailCtaLabel())
    }

    @Test
    fun jsonFileStore_persistsAsyncAndPreservesCorruptBackupFile() = runTest {
        val tempDir = Files.createTempDirectory("aw_store_test").toFile()
        try {
            val target = File(tempDir, "favorites.json")
            target.writeText("{broken-json-payload", Charsets.UTF_8)

            val store = JsonFileStore(
                file = target,
                serializer = ListSerializer(AnimeItem.serializer()),
                defaultValue = emptyList(),
            )

            val initial = store.awaitLoaded()
            assertTrue(initial.isEmpty())

            val backupFile = File(tempDir, "favorites.json.corrupt.bak")
            assertTrue(backupFile.exists())
            assertEquals("{broken-json-payload", backupFile.readText(Charsets.UTF_8))

            store.update { listOf(AnimeItem(id = "7431", title = "Steel Ball Run")) }
            store.flush()

            val reloaded = JsonFileStore(
                file = target,
                serializer = ListSerializer(AnimeItem.serializer()),
                defaultValue = emptyList(),
            ).awaitLoaded()
            assertEquals(1, reloaded.size)
            assertEquals("Steel Ball Run", reloaded.first().title)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun skipTimeRemoteDataSource_serializesGraphQlVariablesSafely() {
        val ds = SkipTimeRemoteDataSource(OkHttpClient())
        val trickyAnime = AnimeItem(
            id = "99",
            title = "Fate/stay night: \"Unlimited\" Blade\nWorks (ITA)",
            isDub = true,
        )
        val payload = ds.buildAnimeSkipGraphQlPayload(trickyAnime)
        assertNotNull(payload)
        val parsed = Json.parseToJsonElement(payload!!).jsonObject
        val searchVar = parsed["variables"]?.jsonObject?.get("search")?.jsonPrimitive?.content
        assertEquals("Fate/stay night: \"Unlimited\" Blade Works", searchVar)
    }

    @Test
    fun historyRepository_marksCompletedAt90PercentAndPreservesMetadata() = runTest {
        val tempDir = Files.createTempDirectory("aw_history_test").toFile()
        try {
            val repo = HistoryRepository(tempDir)
            val rich = AnimeItem(
                id = "100",
                title = "Chainsaw Man",
                posterUrl = "https://cdn.example.com/poster.jpg",
                malId = 44511,
                genres = listOf("Action"),
            )
            repo.updateProgress(rich, episodeNum = "1", videoId = "v1", positionMs = 600_000L, durationMs = 1_500_000L)
            assertFalse(repo.getEpisodeProgress(rich.uniqueKey, "1")!!.completed)

            val sparse = AnimeItem(id = "100", title = "Chainsaw Man")
            repo.updateProgress(sparse, episodeNum = "1", videoId = "v1", positionMs = 1_360_000L, durationMs = 1_500_000L)
            repo.flush()

            val entry = repo.getEntry(rich.uniqueKey)!!
            assertTrue(entry.completed)
            assertEquals(0L, entry.progressMs)
            assertEquals(44511, entry.anime.malId)
            assertEquals("https://cdn.example.com/poster.jpg", entry.anime.posterUrl)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
