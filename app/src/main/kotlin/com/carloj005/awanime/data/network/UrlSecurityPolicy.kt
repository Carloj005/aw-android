package com.carloj005.awanime.data.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.util.Locale

/**
 * Centralized URL and host validation rules (anti-phishing, anti-SSRF, strict HTTPS).
 */
object UrlSecurityPolicy {

    private val ANIME_UNITY_HOST_REGEX = Regex("""^(?:www\.)?animeunity\.[a-z]{2,10}$""")
    private val ANIME_UNITY_ANY_SUBDOMAIN_REGEX = Regex("""^(?:[a-z0-9-]+\.)*animeunity\.[a-z]{2,10}$""")
    private val TRUSTED_EMBED_HOST_REGEX =
        Regex("""^(?:[a-z0-9-]+\.)*(?:vixcloud|vix-content|vixsrc)\.[a-z]{2,10}$""")
    private val IP_LITERAL_REGEX = Regex("""^(?:\d{1,3}(?:\.\d{1,3}){3}|\[?[0-9a-fA-F:]+\]?)$""")

    fun isTrustedAnimeUnityOrigin(rawUrl: String): Boolean {
        val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        val host = parsed.host.lowercase(Locale.ROOT)
        return ANIME_UNITY_HOST_REGEX.matches(host)
    }

    fun isTrustedEmbedHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase(Locale.ROOT)
        return TRUSTED_EMBED_HOST_REGEX.matches(host)
    }

    fun isTrustedEmbedUrl(rawUrl: String): Boolean {
        val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        return isTrustedEmbedHost(parsed.host) || isTrustedAnimeUnityOrigin(rawUrl)
    }

    fun isDohTargetHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase(Locale.ROOT)
        return isTrustedEmbedHost(host) || ANIME_UNITY_ANY_SUBDOMAIN_REGEX.matches(host)
    }

    fun isTrustedMediaUrl(rawUrl: String): Boolean {
        val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        val host = parsed.host.lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) {
            return false
        }
        if (IP_LITERAL_REGEX.matches(host)) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            return DohFallbackDns.isGlobalPublicAddress(addr)
        }
        return host.contains('.')
    }

    private val TRUSTED_GITHUB_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    fun isTrustedGitHubHost(rawHost: String): Boolean {
        return rawHost.trim().lowercase(Locale.ROOT) in TRUSTED_GITHUB_HOSTS
    }

    fun isTrustedGitHubDownloadUrl(rawUrl: String): Boolean {
        val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
        if (parsed.scheme != "https") return false
        return isTrustedGitHubHost(parsed.host)
    }
}
