package com.carloj005.awanime

import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.network.DohFallbackDns
import com.carloj005.awanime.data.network.SearchUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun animeUnityClient_expandsSubAndItaVariantsWithSeparateCounts() {
        val sample = Json.parseToJsonElement(
            """
            {
              "id": 7771,
              "slug": "black-clover-2",
              "title": "Black Clover 2",
              "type": "tv",
              "season": "fall",
              "year": 2026,
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
        assertFalse(expanded[0].isDub)
        assertEquals(8, expanded[0].releasedEpisodes)

        assertEquals("Black Clover 2 (ITA)", expanded[1].title)
        assertTrue(expanded[1].isDub)
        assertEquals(5, expanded[1].releasedEpisodes)
    }

    @Test
    fun antiPhishingAndDohValidation_rejectInvalidTargets() {
        assertFalse(AnimeUnityClient.isValidAnimeUnityHtml("<html><body>Fake Clone</body></html>"))
        assertTrue(
            AnimeUnityClient.isValidAnimeUnityHtml(
                """<div id="app" data-page="{&quot;props&quot;:{&quot;scws_url&quot;:&quot;https://vixcloud.cc&quot;}}"></div>"""
            )
        )
        assertFalse(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("127.0.0.1") as Inet4Address))
        assertFalse(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("169.254.169.254") as Inet4Address))
        assertFalse(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("192.168.1.1") as Inet4Address))
        assertTrue(DohFallbackDns.isGlobalPublicIpv4(InetAddress.getByName("104.21.55.12") as Inet4Address))
    }
}
