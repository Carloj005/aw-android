package com.carloj005.awanime.data.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.carloj005.awanime.data.network.UrlSecurityPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

sealed interface AnimeUnityDeepLink {
    data class AnimeDetail(
        val animeId: String,
        val slug: String = "",
        val isDubHint: Boolean = false,
    ) : AnimeUnityDeepLink

    data class EpisodePlayback(
        val animeId: String,
        val slug: String = "",
        val videoId: String,
        val isDubHint: Boolean = false,
    ) : AnimeUnityDeepLink

    data object Calendar : AnimeUnityDeepLink

    data class Search(val query: String) : AnimeUnityDeepLink

    data class Home(val trending: Boolean = false) : AnimeUnityDeepLink
}

/**
 * Pure, security-hardened parser for incoming AnimeUnity web links
 * (anime detail, direct episode/video URL, calendar, archive/search, top-anime).
 */
object AnimeUnityDeepLinkParser {

    private val ANIME_SEGMENT_REGEX = Regex("""^(\d+)(?:-(.+))?$""")
    private val NUMERIC_ID_REGEX = Regex("""^\d{1,12}$""")

    fun parse(rawUrl: String?): AnimeUnityDeepLink? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" && parsed.scheme != "http") return null

        // Enforce strict AnimeUnity domain allowlist (rejects lookalike/phishing hosts)
        val httpsOrigin = "https://${parsed.host.lowercase(Locale.ROOT)}"
        if (!UrlSecurityPolicy.isTrustedAnimeUnityOrigin(httpsOrigin)) return null

        val segments = parsed.pathSegments.filter { it.isNotBlank() }
        if (segments.isEmpty()) return AnimeUnityDeepLink.Home(trending = false)

        val head = segments[0].lowercase(Locale.ROOT)
        return when (head) {
            "anime" -> parseAnimePath(segments, parsed.queryParameter("language"), parsed.queryParameter("dub"))
            "calendario", "calendar" -> AnimeUnityDeepLink.Calendar
            "archivio", "search" -> {
                val query = (parsed.queryParameter("title")
                    ?: parsed.queryParameter("query")
                    ?: parsed.queryParameter("q"))
                    ?.trim()
                    ?.take(100)
                    .orEmpty()
                if (query.isNotEmpty()) {
                    AnimeUnityDeepLink.Search(query)
                } else {
                    AnimeUnityDeepLink.Home(trending = false)
                }
            }
            "top-anime", "tendenze" -> AnimeUnityDeepLink.Home(trending = true)
            else -> AnimeUnityDeepLink.Home(trending = false)
        }
    }

    private fun parseAnimePath(
        segments: List<String>,
        languageParam: String?,
        dubParam: String?,
    ): AnimeUnityDeepLink? {
        val idAndSlug = segments.getOrNull(1)?.trim() ?: return null
        val match = ANIME_SEGMENT_REGEX.matchEntire(idAndSlug) ?: return null
        val animeId = match.groupValues[1]
        if (!NUMERIC_ID_REGEX.matches(animeId)) return null
        val slug = match.groupValues.getOrNull(2).orEmpty().trim()

        val slugLower = slug.lowercase(Locale.ROOT)
        val isDubHint = slugLower.endsWith("-ita") ||
            slugLower == "ita" ||
            languageParam.equals("ita", ignoreCase = true) ||
            languageParam.equals("dub", ignoreCase = true) ||
            dubParam == "1" ||
            dubParam.equals("true", ignoreCase = true)

        val videoSegment = segments.getOrNull(2)?.trim()
        if (videoSegment != null && NUMERIC_ID_REGEX.matches(videoSegment)) {
            return AnimeUnityDeepLink.EpisodePlayback(
                animeId = animeId,
                slug = slug,
                videoId = videoSegment,
                isDubHint = isDubHint,
            )
        }

        return AnimeUnityDeepLink.AnimeDetail(
            animeId = animeId,
            slug = slug,
            isDubHint = isDubHint,
        )
    }

    fun slugToDisplayTitle(slug: String): String {
        val clean = slug.trim()
            .replace(Regex("""-+"""), " ")
            .trim()
        if (clean.isEmpty()) return "Anime"
        return clean.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch -> ch.titlecase(Locale.ITALIAN) }
            }
    }

    /**
     * Opens Android's "Open by default / Supported web addresses" settings screen on Android 12+
     * (falling back to App Info on older Android versions) so the user can enable 1-click opening
     * of AnimeUnity links in AW Anime.
     */
    fun openSupportedLinksSettings(context: Context): Boolean {
        val pkgUri = Uri.parse("package:${context.packageName}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val openByDefault = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkgUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(openByDefault) }.isSuccess) {
                return true
            }
        }
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(appDetails)
            true
        }.getOrDefault(false)
    }
}
