package com.carloj005.awanime

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.AppBackupData
import com.carloj005.awanime.data.model.AppSettings
import com.carloj005.awanime.data.model.SkipKind
import com.carloj005.awanime.data.model.ThemeMode
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.network.DohFallbackDns
import com.carloj005.awanime.data.network.SearchUtils
import com.carloj005.awanime.data.update.AppUpdater
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

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
    fun animeUnityClient_expandsSubAndItaVariantsWithMalId() {
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
                {"language": "ita", "status": "ongoing", "episodes_count": 5},
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

        assertEquals("Black Clover 2 (ITA)", expanded[1].title)
        assertTrue(expanded[1].isDub)
        assertEquals(5, expanded[1].releasedEpisodes)
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
    fun appUpdater_validatesVersionsTrustedHostsAndSha256() {
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
        assertEquals(validHash, AppUpdater.parseSha256Line("$validHash  AW-Anime-v1.2.0.apk\n"))
        assertNull(AppUpdater.parseSha256Line("not-a-valid-sha256-hash"))
    }

    @Test
    fun antiPhishingHostAllowlistsAndDohIpv4Ipv6Validation_rejectInvalidTargets() {
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

        assertTrue(AnimeUnityClient.isTrustedEmbedUrl("https://vixcloud.cc/embed/307688?canPlayFHD=1"))
        assertFalse(AnimeUnityClient.isTrustedEmbedUrl("http://vixcloud.cc/embed/307688"))
        assertFalse(AnimeUnityClient.isTrustedEmbedUrl("https://127.0.0.1/embed/1"))

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
}
