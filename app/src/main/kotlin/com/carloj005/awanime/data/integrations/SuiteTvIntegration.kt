package com.carloj005.awanime.data.integrations

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.WatchHistoryEntry
import com.carloj005.awanime.data.network.SearchUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SuiteTvExportResult(
    val jsonPayload: String,
    val showCount: Int,
    val episodeCount: Int,
)

/**
 * Integration with Suite TV (https://www.suitetv.app/it, native Android app `com.nicolasbordeaux.suitetv`).
 *
 * Uses verified Android App Links (`https://www.suitetv.app/it/...`) to open anime directly inside the
 * installed Suite TV app (falling back to web when not installed), and generates a standard Trakt-compatible
 * JSON watch-history export natively supported by Suite TV's built-in importer.
 */
object SuiteTvIntegration {

    const val SUITE_TV_PACKAGE = "com.nicolasbordeaux.suitetv"
    const val SUITE_TV_HOME_URL = "https://www.suitetv.app/it"
    const val SUITE_TV_SEARCH_BASE_URL = "https://www.suitetv.app/it/search"

    private val prettyJson = Json { prettyPrint = true }
    private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    private val SEASON_EXTRACTION_REGEXES = listOf(
        Regex("""\b(?:season|stagione)\s*(\d+)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d+)(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE),
        Regex("""\bS(\d{1,2})\b"""),
    )

    private val TITLE_SUFFIX_CLEAN_REGEXES = listOf(
        Regex("""\s*[-–:]?\s*\b(?:season|stagione)\s*\d+\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–:]?\s*\b\d+(?:st|nd|rd|th)\s+season\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–:]?\s*\b(?:part|parte|cour)\s*\d+\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+\bS\d{1,2}\b$"""),
    )

    fun isSuiteTvInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SUITE_TV_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /**
     * Strips language tags `(ITA)`/`(SUB)` and trailing season/part qualifiers so Suite TV's
     * TVDB-backed search resolves the parent show cleanly.
     */
    fun cleanTitleForSuiteTv(rawTitle: String): String {
        var cleaned = SearchUtils.stripDubTags(rawTitle)
            .replace(Regex("""\((?:SUB|SUB\s*ITA|DUB|UNCENSORED|CENSURATO)\)""", RegexOption.IGNORE_CASE), "")
            .trim()
        for (regex in TITLE_SUFFIX_CLEAN_REGEXES) {
            val stripped = cleaned.replace(regex, "").trim()
            if (stripped.length >= 2) {
                cleaned = stripped
            }
        }
        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Infers season number from localized/English anime titles (e.g. "Jujutsu Kaisen Season 2" -> 2).
     * Defaults to 1 when no explicit season indicator is present.
     */
    fun extractSeasonNumber(rawTitle: String): Int {
        for (regex in SEASON_EXTRACTION_REGEXES) {
            val num = regex.find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (num != null && num in 1..50) return num
        }
        return 1
    }

    fun buildSearchUrl(animeTitle: String): String {
        val clean = cleanTitleForSuiteTv(animeTitle)
        if (clean.isBlank()) return SUITE_TV_HOME_URL
        val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name())
        return "$SUITE_TV_SEARCH_BASE_URL?q=$encoded"
    }

    /**
     * Opens the anime directly inside the native Suite TV Android app when installed:
     * 1) Tries a package-scoped App Link (`setPackage(SUITE_TV_PACKAGE)`) if Suite TV declares an
     *    intent-filter for `/it/search`.
     * 2) Otherwise copies the cleaned series title to the clipboard, shows a quick Toast hint,
     *    and launches the native Suite TV app directly (never unexpectedly kicking to Chrome).
     * 3) Falls back to Suite TV Web only if the Suite TV app is not installed.
     */
    fun openAnimeInSuiteTv(context: Context, anime: AnimeItem): Boolean {
        val cleanTitle = cleanTitleForSuiteTv(anime.title).ifBlank { anime.title.trim() }
        val url = buildSearchUrl(anime.title)
        val pm = context.packageManager

        if (isSuiteTvInstalled(context)) {
            val appScopedView = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(SUITE_TV_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (appScopedView.resolveActivity(pm) != null) {
                if (runCatching { context.startActivity(appScopedView) }.isSuccess) {
                    return true
                }
            }

            // Suite TV Android does not claim /search via VIEW intent: copy title + launch native app directly
            if (cleanTitle.isNotBlank()) {
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Suite TV", cleanTitle))
                }
                runCatching {
                    Toast.makeText(
                        context.applicationContext,
                        "Titolo copiato: $cleanTitle — incollalo in Suite TV",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }

            val launchIntent = pm.getLaunchIntentForPackage(SUITE_TV_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(launchIntent) }.isSuccess) {
                    return true
                }
            }
        }

        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(webIntent)
            true
        }.getOrDefault(false)
    }

    fun openSuiteTvAppOrWeb(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SUITE_TV_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(launchIntent) }.isSuccess) {
                return true
            }
        }
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SUITE_TV_HOME_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(webIntent)
            true
        }.getOrDefault(false)
    }

    /**
     * Builds a standard Trakt v2 watched-history JSON array (`watched_at`, `action`, `type`,
     * `episode { season, number }`, `show { title, year, ids { slug } }`) containing all completed
     * episodes in the user's AW Anime watch history so it can be imported directly into Suite TV.
     */
    fun buildTraktHistoryExport(history: List<WatchHistoryEntry>): SuiteTvExportResult {
        val distinctShows = LinkedHashSet<String>()
        var episodeCount = 0

        val array: JsonArray = buildJsonArray {
            for (entry in history) {
                val showTitle = cleanTitleForSuiteTv(entry.anime.title).ifBlank { entry.anime.title.trim() }
                val seasonNum = extractSeasonNumber(entry.anime.title)
                val showSlug = entry.anime.slug.takeIf { it.isNotBlank() }
                    ?: showTitle.lowercase(Locale.ROOT)
                        .replace(Regex("""[^a-z0-9]+"""), "-")
                        .trim('-')

                val completedEps = entry.episodesProgress.values
                    .filter { it.completed }
                    .sortedBy { it.updatedAt }

                for (ep in completedEps) {
                    val epNum = ep.episodeNum.trim().split(".", "-").firstOrNull()?.toIntOrNull()
                        ?: continue
                    if (epNum <= 0) continue

                    val ts = (ep.updatedAt.takeIf { it > 0L } ?: entry.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
                    val watchedAtIso = ISO_UTC_FORMATTER.format(Instant.ofEpochMilli(ts))

                    distinctShows.add(showTitle.lowercase(Locale.ROOT))
                    episodeCount++

                    add(
                        buildJsonObject {
                            put("watched_at", watchedAtIso)
                            put("action", "watch")
                            put("type", "episode")
                            putJsonObject("episode") {
                                put("season", seasonNum)
                                put("number", epNum)
                            }
                            putJsonObject("show") {
                                put("title", showTitle)
                                entry.anime.year?.let { put("year", it) }
                                putJsonObject("ids") {
                                    put("slug", showSlug)
                                }
                            }
                        }
                    )
                }
            }
        }

        val payload = prettyJson.encodeToString(JsonArray.serializer(), array)
        return SuiteTvExportResult(
            jsonPayload = payload,
            showCount = distinctShows.size,
            episodeCount = episodeCount,
        )
    }
}
