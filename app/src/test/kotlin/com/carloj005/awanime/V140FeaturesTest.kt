package com.carloj005.awanime

import com.carloj005.awanime.data.integrations.BugReportHelper
import com.carloj005.awanime.data.integrations.ReportKind
import com.carloj005.awanime.data.integrations.SuiteTvIntegration
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.SearchSortOrder
import com.carloj005.awanime.data.model.TrackedNotificationPrefs
import com.carloj005.awanime.data.network.SearchUtils
import com.carloj005.awanime.data.notifications.SequelTracker
import com.carloj005.awanime.data.storage.TrackedNotificationsRepository
import com.carloj005.awanime.data.update.AppUpdater
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class V140FeaturesTest {

    @Test
    fun trackedNotificationsRepository_respectsPerAnimeOptInAndMuteOverrides() = runTest {
        val tempDir = Files.createTempDirectory("aw_tracked_notif_test").toFile()
        try {
            val repo = TrackedNotificationsRepository(tempDir)
            val favKeys = setOf("100_sub", "200_ita")
            val watchingKeys = setOf("300_sub")

            // Defaults: favorites & watching enabled, unknown anime disabled
            assertTrue(repo.isEnabledForAnime("100_sub", favKeys, watchingKeys))
            assertTrue(repo.isEnabledForAnime("200_ita", favKeys, watchingKeys))
            assertTrue(repo.isEnabledForAnime("300_sub", favKeys, watchingKeys))
            assertFalse(repo.isEnabledForAnime("999_sub", favKeys, watchingKeys))

            // Explicit mute overrides even favorites & watching
            repo.setAnimeNotificationEnabled("100_sub", enabled = false)
            repo.setAnimeNotificationEnabled("300_sub", enabled = false)
            assertFalse(repo.isEnabledForAnime("100_sub", favKeys, watchingKeys))
            assertFalse(repo.isEnabledForAnime("300_sub", favKeys, watchingKeys))

            // Explicit enable tracks an anime outside favorites/watching
            repo.setAnimeNotificationEnabled("999_sub", enabled = true)
            assertTrue(repo.isEnabledForAnime("999_sub", favKeys, watchingKeys))

            // Re-enabling a previously muted favorite clears its mute state
            repo.setAnimeNotificationEnabled("100_sub", enabled = true)
            assertTrue(repo.isEnabledForAnime("100_sub", favKeys, watchingKeys))

            // Discovered sequels persist and merge cleanly
            repo.markSequelDiscovered("5801_sub")
            repo.mergeImport(
                TrackedNotificationPrefs(
                    explicitlyEnabledKeys = setOf("777_sub"),
                    explicitlyMutedKeys = setOf("200_ita"),
                    discoveredSequelKeys = setOf("9001_sub"),
                )
            )
            repo.flush()

            val reloaded = TrackedNotificationsRepository(tempDir)
            val prefs = reloaded.awaitLoaded()
            assertTrue("5801_sub" in prefs.discoveredSequelKeys)
            assertTrue("9001_sub" in prefs.discoveredSequelKeys)
            assertTrue("777_sub" in prefs.explicitlyEnabledKeys)
            assertFalse(reloaded.isEnabledForAnime("200_ita", favKeys, watchingKeys))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun sequelTracker_findNextSeasonPrefersMainlineSequelAndAdaptsLanguage() {
        val s1Sub = AnimeItem(
            id = "4290",
            title = "Solo Leveling",
            type = "TV",
            season = "Inverno",
            year = 2024,
            isDub = false,
            hasSubVariant = true,
            hasDubVariant = true,
        )
        val ova = AnimeItem(
            id = "5100",
            title = "Solo Leveling: ReAwakening",
            type = "Special",
            season = "Autunno",
            year = 2024,
            isDub = false,
        )
        val s2Sub = AnimeItem(
            id = "5801",
            title = "Solo Leveling 2: Arise from the Shadow",
            type = "TV",
            season = "Inverno",
            year = 2025,
            isDub = false,
            hasSubVariant = true,
            hasDubVariant = true,
        )

        // From S1 SUB -> skips Special and resolves S2 SUB
        val nextForSub = SequelTracker.findNextSeason(s1Sub, listOf(s2Sub, ova, s1Sub))
        assertNotNull(nextForSub)
        assertEquals("5801", nextForSub!!.id)
        assertFalse(nextForSub.isDub)

        // From S1 ITA -> resolves S2 adapted to ITA when dub variant exists
        val s1Dub = s1Sub.copy(title = "Solo Leveling (ITA)", isDub = true, audioLabel = "Italiano")
        val nextForDub = SequelTracker.findNextSeason(s1Dub, listOf(s1Sub, s2Sub))
        assertNotNull(nextForDub)
        assertEquals("5801", nextForDub!!.id)
        assertTrue(nextForDub.isDub)
        assertEquals("Solo Leveling 2: Arise from the Shadow (ITA)", nextForDub.title)

        // From final known season S2 -> returns null
        assertNull(SequelTracker.findNextSeason(s2Sub, listOf(s1Sub, s2Sub)))
    }

    @Test
    fun sequelTracker_detectNewSeasonsInFeedFindsFutureSeasonAndRespectsLanguageAndDedup() {
        val watchedS1 = AnimeItem(id = "4290", title = "Solo Leveling", year = 2024, isDub = false)
        val watchedS2 = AnimeItem(id = "5801", title = "Solo Leveling 2: Arise from the Shadow", year = 2025, isDub = false)
        val futureS3Sub = AnimeItem(id = "8800", title = "Solo Leveling Season 3", year = 2027, isDub = false)
        val futureS3Dub = AnimeItem(id = "8800", title = "Solo Leveling Season 3 (ITA)", year = 2027, isDub = true)
        val unrelated = AnimeItem(id = "9999", title = "Dandadan", year = 2027, isDub = false)

        val discovered = SequelTracker.detectNewSeasonsInFeed(
            userLibrary = listOf(watchedS1, watchedS2),
            feedCandidates = listOf(watchedS2, futureS3Sub, futureS3Dub, unrelated),
            knownUserKeys = setOf(watchedS1.uniqueKey, watchedS2.uniqueKey),
            alreadyDiscoveredKeys = emptySet(),
        )
        assertEquals(1, discovered.size)
        assertEquals("8800", discovered[0].newSeason.id)
        assertFalse(discovered[0].newSeason.isDub)
        assertEquals("5801", discovered[0].parentAnime.id)

        // Already discovered keys are not reported again
        val secondPass = SequelTracker.detectNewSeasonsInFeed(
            userLibrary = listOf(watchedS1, watchedS2),
            feedCandidates = listOf(futureS3Sub),
            knownUserKeys = setOf(watchedS1.uniqueKey, watchedS2.uniqueKey),
            alreadyDiscoveredKeys = setOf(futureS3Sub.uniqueKey),
        )
        assertTrue(secondPass.isEmpty())
    }

    @Test
    fun searchUtils_expandsAliasesAndSortsByUserOrder() {
        assertEquals("attack on titan", SearchUtils.expandQueryAlias("AOT"))
        assertEquals("jujutsu kaisen", SearchUtils.expandQueryAlias("jjk"))
        assertEquals("chainsaw man", SearchUtils.expandQueryAlias("csm"))
        assertEquals("solo leveling", SearchUtils.expandQueryAlias("SoloLeveling"))
        assertEquals("steel ball run", SearchUtils.expandQueryAlias("sbr"))
        assertEquals("Frieren", SearchUtils.expandQueryAlias("  Frieren  "))

        val lowOld = AnimeItem(id = "1", title = "Alpha", year = 2015, score = "7.10", views = 5_000)
        val highMid = AnimeItem(id = "2", title = "Beta", year = 2021, score = "9.25", views = 20_000)
        val popNew = AnimeItem(id = "3", title = "Gamma", year = 2025, score = "8.40", views = 900_000)
        val pool = listOf(lowOld, highMid, popNew)

        assertEquals(
            listOf("2", "3", "1"),
            SearchUtils.sortByUserOrder(pool, "", SearchSortOrder.SCORE).map { it.id },
        )
        assertEquals(
            listOf("3", "2", "1"),
            SearchUtils.sortByUserOrder(pool, "", SearchSortOrder.RECENT).map { it.id },
        )
        assertEquals(
            listOf("3", "2", "1"),
            SearchUtils.sortByUserOrder(pool, "", SearchSortOrder.POPULAR).map { it.id },
        )
    }

    @Test
    fun bugReportHelper_buildsEncodedGitHubIssueUrlAndDiagnostics() {
        val url = BugReportHelper.buildGitHubIssueUrl(
            kind = ReportKind.BUG,
            rawTitle = "Player freeze su PiP",
            description = "Succede aprendo l'episodio 3",
            activeDomain = "animeunity.cx",
            repo = "Carloj005/aw-android",
            versionName = "1.4.0",
            versionCode = 10,
            androidRelease = "15",
            sdkInt = 35,
            deviceLabel = "Google Pixel 9",
        )
        assertTrue(url.startsWith("https://github.com/Carloj005/aw-android/issues/new?"))
        assertTrue(url.contains("labels=bug"))
        assertTrue(url.contains("title=%5Bbug%5D+Player+freeze+su+PiP"))
        assertTrue(url.contains("AW+Anime+v1.4.0"))
        assertTrue(url.contains("animeunity.cx"))

        val body = BugReportHelper.formatDiagnosticBody(
            description = "Dettaglio errore",
            activeDomain = "animeunity.cx",
            versionName = "1.4.0",
            versionCode = 10,
            androidRelease = "15",
            sdkInt = 35,
            deviceLabel = "Pixel 9",
        )
        assertTrue(body.contains("Dettaglio errore"))
        assertTrue(body.contains("AW Anime v1.4.0 (build 10)"))
        assertTrue(body.contains("Android**: 15 (API 35)"))
        assertTrue(body.contains("Pixel 9"))
    }

    @Test
    fun appUpdater_parsesReleaseNotesStrippingFullChangelogLinksAndRawMarkdown() {
        val rawGitHubAutoNotes = """
            ## What's Changed
            **Full Changelog**: https://github.com/Carloj005/aw-android/compare/v1.3.2...v1.4.0
        """.trimIndent()
        val fallbackEntries = AppUpdater.parseChangelogEntries(rawGitHubAutoNotes, "v1.4.0")
        assertEquals(1, fallbackEntries.size)
        assertFalse(fallbackEntries[0].text.contains("Full Changelog", ignoreCase = true))
        assertTrue(fallbackEntries[0].text.contains("v1.4.0"))

        val richNotes = """
            ✨ Novità
            • Calendario personalizzato: mostra solo gli anime seguiti.
            🛠️ Correzioni
            - **Fix Suite TV**: apre direttamente `com.nicolasbordeaux.suitetv`.
            **Full Changelog**: https://github.com/Carloj005/aw-android/compare/v1.3.2...v1.4.0
        """.trimIndent()
        val entries = AppUpdater.parseChangelogEntries(richNotes, "v1.4.0")
        assertEquals(4, entries.size)
        assertTrue(entries[0].isSectionHeader)
        assertEquals("✨ Novità", entries[0].text)
        assertEquals("Calendario personalizzato", entries[1].title)
        assertEquals("mostra solo gli anime seguiti.", entries[1].text)
        assertTrue(entries[2].isSectionHeader)
        assertEquals("🛠️ Correzioni", entries[2].text)
        assertEquals("Fix Suite TV", entries[3].title)
        assertEquals("apre direttamente com.nicolasbordeaux.suitetv.", entries[3].text)
    }

    @Test
    fun sequelTracker_calendarLanguageFilterExcludesSubWhenUserOnlyWatchesIta() {
        val jojo7Ita = AnimeItem(
            id = "7808",
            title = "Le Bizzarre Avventure di JoJo: Steel Ball Run (ITA)",
            year = 2026,
            isDub = true,
        )
        val jojo7Sub = AnimeItem(
            id = "7808",
            title = "Le Bizzarre Avventure di JoJo: Steel Ball Run",
            year = 2026,
            isDub = false,
        )
        val jojo8Sub = AnimeItem(
            id = "9900",
            title = "Le Bizzarre Avventure di JoJo: JoJolion",
            year = 2028,
            isDub = false,
        )
        val jojo8Ita = AnimeItem(
            id = "9900",
            title = "Le Bizzarre Avventure di JoJo: JoJolion (ITA)",
            year = 2028,
            isDub = true,
        )

        val profiles = SequelTracker.buildUserFranchiseProfiles(listOf(jojo7Ita))
        val knownIds = setOf(jojo7Ita.id)

        // Same anime in SUB must NEVER appear in personalized Calendar
        assertFalse(SequelTracker.isCalendarFranchiseSequel(jojo7Sub, profiles, knownIds))
        // Future season in SUB must NOT appear when user only follows ITA
        assertFalse(SequelTracker.isCalendarFranchiseSequel(jojo8Sub, profiles, knownIds))
        // Future season in ITA MUST appear
        assertTrue(SequelTracker.isCalendarFranchiseSequel(jojo8Ita, profiles, knownIds))
    }

    @Test
    fun suiteTvIntegration_cleansArcSubtitlesBuildsDirectUrlsAndImportsJsonFormats() {
        assertEquals("Tougen Anki", SuiteTvIntegration.cleanTitleForSuiteTv("Tougen Anki: Nikko Kegon Falls Arc"))
        assertEquals(
            "Le Bizzarre Avventure di JoJo",
            SuiteTvIntegration.cleanTitleForSuiteTv("Le Bizzarre Avventure di JoJo: Steel Ball Run 2nd - 3rd Stage (ITA)"),
        )
        assertEquals("Solo Leveling", SuiteTvIntegration.cleanTitleForSuiteTv("Solo Leveling 2: Arise from the Shadow"))
        assertEquals("Mob Psycho 100", SuiteTvIntegration.cleanTitleForSuiteTv("Mob Psycho 100"))
        assertEquals("Re:Zero", SuiteTvIntegration.cleanTitleForSuiteTv("Re:Zero - Starting Life in Another World"))
        assertEquals("https://www.suitetv.app/it/series/449843", SuiteTvIntegration.buildDirectSeriesUrl(449843))

        // 1. Trakt v2 JSON import
        val traktJson = """
            [
              {
                "watched_at": "2026-11-20T18:00:00.000Z",
                "action": "watch",
                "type": "episode",
                "episode": { "season": 2, "number": 1 },
                "show": { "title": "Solo Leveling", "year": 2025, "ids": { "tvdb": 389597 } }
              },
              {
                "watched_at": "2026-11-21T18:00:00.000Z",
                "action": "watch",
                "type": "episode",
                "episode": { "season": 2, "number": 2 },
                "show": { "title": "Solo Leveling", "year": 2025, "ids": { "tvdb": 389597 } }
              }
            ]
        """.trimIndent()
        val traktParsed = SuiteTvIntegration.parseImportedSuiteTvJson(traktJson)
        assertEquals(1, traktParsed.size)
        assertEquals("Solo Leveling", traktParsed[0].title)
        assertEquals(2, traktParsed[0].seasonNumber)
        assertEquals(389597, traktParsed[0].tvdbId)
        assertEquals(setOf(1, 2), traktParsed[0].watchedEpisodes)

        // 2. Suite TV / TV Time Liberator JSON import (skips unwatched and specials)
        val tvTimeJson = """
            {
              "shows": [
                {
                  "title": "Tougen Anki",
                  "id": { "tvdb": 449843 },
                  "episodes": [
                    { "number": 1, "special": false, "is_watched": true, "watched_at": "2026-10-01 20:00:00" },
                    { "number": 2, "special": false, "is_watched": true, "watched_at": "2026-10-08 20:00:00" },
                    { "number": 3, "special": false, "is_watched": false },
                    { "number": 1, "special": true, "is_watched": true }
                  ]
                }
              ]
            }
        """.trimIndent()
        val tvTimeParsed = SuiteTvIntegration.parseImportedSuiteTvJson(tvTimeJson)
        assertEquals(1, tvTimeParsed.size)
        assertEquals("Tougen Anki", tvTimeParsed[0].title)
        assertEquals(449843, tvTimeParsed[0].tvdbId)
        assertEquals(setOf(1, 2), tvTimeParsed[0].watchedEpisodes)
    }
}
