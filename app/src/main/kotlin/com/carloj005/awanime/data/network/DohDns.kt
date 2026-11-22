package com.carloj005.awanime.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Custom OkHttp DNS con fallback trasparente DNS-over-HTTPS (Cloudflare / Google)
 * contro blocchi o avvelenamenti DNS ISP italiani, filtrando rigorosamente IP privati/loopback.
 */
class DohFallbackDns : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
    private val json = Json { ignoreUnknownKeys = true }
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val targetKeywords = listOf(
        "animeunity.",
        "vixcloud.",
        "vix-content.",
    )

    private val dohEndpoints = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://1.1.1.1/dns-query",
        "https://dns.google/resolve",
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val lower = hostname.lowercase()
        val isTarget = targetKeywords.any { it in lower }

        if (isTarget) {
            cache[lower]?.let { return it }
        }

        try {
            val system = Dns.SYSTEM.lookup(hostname)
            if (isTarget && system.isNotEmpty()) {
                val poisoned = system.all { addr ->
                    addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isSiteLocalAddress
                }
                if (poisoned) {
                    resolveViaDoh(lower)?.let { return it }
                }
            }
            return system
        } catch (e: UnknownHostException) {
            if (isTarget) {
                resolveViaDoh(lower)?.let { return it }
            }
            throw e
        }
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress>? {
        for (endpoint in dohEndpoints) {
            try {
                val req = Request.Builder()
                    .url("$endpoint?name=$hostname&type=A")
                    .header("Accept", "application/dns-json")
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val root = json.parseToJsonElement(body).jsonObject
                    val answers = root["Answer"]?.jsonArray ?: return@use
                    val resolved = mutableListOf<InetAddress>()
                    for (ans in answers) {
                        val ipStr = ans.jsonObject["data"]?.jsonPrimitive?.content?.trim() ?: continue
                        val addr = runCatching { InetAddress.getByName(ipStr) }.getOrNull() ?: continue
                        if (addr is Inet4Address && isGlobalPublicIpv4(addr)) {
                            resolved.add(addr)
                        }
                    }
                    if (resolved.isNotEmpty()) {
                        cache[hostname] = resolved
                        return resolved
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    companion object {
        fun isGlobalPublicIpv4(addr: Inet4Address): Boolean {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress
            ) {
                return false
            }
            val b = addr.address
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            // Blocca CGNAT 100.64.0.0/10 e riservati 0.0.0.0/8, 240.0.0.0/4
            if (b0 == 0 || b0 >= 224) return false
            if (b0 == 100 && b1 in 64..127) return false
            return true
        }
    }
}

/**
 * CookieJar in memoria thread-safe per mantenere la sessione Laravel (`XSRF-TOKEN`, `animeunity_session`).
 */
class MemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host.lowercase()
        val list = store.getOrPut(host) { mutableListOf() }
        synchronized(list) {
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                list.removeAll { it.name == cookie.name && it.path == cookie.path }
                if (cookie.expiresAt >= now) {
                    list.add(cookie)
                }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host.lowercase()
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        for ((storedHost, list) in store) {
            if (host == storedHost || host.endsWith(".$storedHost")) {
                synchronized(list) {
                    list.removeAll { it.expiresAt < now }
                    result.addAll(list.filter { it.matches(url) })
                }
            }
        }
        return result
    }
}
