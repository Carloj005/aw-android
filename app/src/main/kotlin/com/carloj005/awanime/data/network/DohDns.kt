package com.carloj005.awanime.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Custom OkHttp DNS con:
 * - Fallback trasparente DNS-over-HTTPS (Cloudflare / Google) contro blocchi ISP
 * - Cache con TTL (10 minuti) per evitare IP stantii dopo rotazione CDN
 * - Protezione anti-SSRF / anti-DNS-rebinding su IPv4 e IPv6 (loopback, privati,
 *   link-local, ULA, CGNAT, IPv4-mapped/compatible, NAT64, multicast e documentazione)
 */
class DohFallbackDns : Dns {
    private data class CachedEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedEntry>()
    private val json = Json { ignoreUnknownKeys = true }

    // Usa intenzionalmente Dns.SYSTEM per evitare ricorsione DNS durante il lookup DoH.
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val dohEndpoints = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://1.1.1.1/dns-query",
        "https://dns.google/resolve",
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val lower = hostname.trim().lowercase()
        val isTarget = isTargetHostname(lower)
        val now = System.currentTimeMillis()

        if (isTarget) {
            cache[lower]?.takeIf { it.expiresAtMs > now }?.let { return it.addresses }
        }

        try {
            val system = Dns.SYSTEM.lookup(hostname)
            val publicOnly = system.filter { isGlobalPublicAddress(it) }
            if (isTarget && (publicOnly.isEmpty() || publicOnly.size < system.size)) {
                resolveViaDoh(lower)?.let { return it }
            }
            if (publicOnly.isNotEmpty()) {
                return publicOnly
            }
            if (isTarget) {
                resolveViaDoh(lower)?.let { return it }
            }
            throw UnknownHostException("Risoluzione DNS bloccata: nessun IP pubblico valido per $hostname")
        } catch (e: UnknownHostException) {
            if (isTarget) {
                resolveViaDoh(lower)?.let { return it }
            }
            throw e
        }
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress>? {
        if (!SAFE_HOSTNAME_REGEX.matches(hostname)) return null
        for (endpoint in dohEndpoints) {
            try {
                val base = endpoint.toHttpUrlOrNull() ?: continue
                val url = base.newBuilder()
                    .addQueryParameter("name", hostname)
                    .addQueryParameter("type", "A")
                    .build()
                val req = Request.Builder()
                    .url(url)
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
                        cache[hostname] = CachedEntry(resolved, System.currentTimeMillis() + CACHE_TTL_MS)
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
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private val SAFE_HOSTNAME_REGEX = Regex("""^[a-z0-9.-]{1,253}$""")
        private val TARGET_HOST_REGEX =
            Regex("""^(?:[a-z0-9-]+\.)*(?:animeunity|vixcloud|vix-content|vixsrc)\.[a-z]{2,10}$""")

        fun isTargetHostname(hostname: String): Boolean {
            return TARGET_HOST_REGEX.matches(hostname.trim().lowercase())
        }

        fun isGlobalPublicAddress(addr: InetAddress): Boolean {
            return when (addr) {
                is Inet4Address -> isGlobalPublicIpv4(addr)
                is Inet6Address -> isGlobalPublicIpv6(addr)
                else -> false
            }
        }

        fun isGlobalPublicIpv4(addr: Inet4Address): Boolean {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress
            ) {
                return false
            }
            val b = addr.address
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            // Blocca 0.0.0.0/8, CGNAT 100.64.0.0/10, benchmark 198.18.0.0/15, multicast/riservati >= 224
            if (b0 == 0 || b0 >= 224) return false
            if (b0 == 100 && b1 in 64..127) return false
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false
            return true
        }

        fun isGlobalPublicIpv6(addr: Inet6Address): Boolean {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress
            ) {
                return false
            }
            val b = addr.address
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            val b3 = b[3].toInt() and 0xFF

            // Blocca Unique Local Address (fc00::/7)
            if ((b0 and 0xFE) == 0xFC) return false
            // Blocca documentation prefix 2001:db8::/32
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0D && b3 == 0xB8) return false
            // Blocca discard prefix 100::/64
            if (b0 == 0x01 && b1 == 0x00 && (2..7).all { b[it].toInt() == 0 }) return false
            // Blocca IPv4-compatible IPv6 (::/96)
            if ((0..11).all { b[it].toInt() == 0 }) return false
            // Verifica IPv4-mapped IPv6 (::ffff:0:0/96) contro regole IPv4 pubbliche
            if ((0..9).all { b[it].toInt() == 0 } &&
                (b[10].toInt() and 0xFF) == 0xFF &&
                (b[11].toInt() and 0xFF) == 0xFF
            ) {
                return isEmbeddedIpv4Public(b)
            }
            // Blocca NAT64 local-use (64:ff9b:1::/48)
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B &&
                b[4].toInt() == 0 && (b[5].toInt() and 0xFF) == 0x01
            ) {
                return false
            }
            // Verifica NAT64 well-known prefix (64:ff9b::/96) contro regole IPv4 pubbliche
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xFF && b3 == 0x9B &&
                (4..11).all { b[it].toInt() == 0 }
            ) {
                return isEmbeddedIpv4Public(b)
            }
            return true
        }

        private fun isEmbeddedIpv4Public(ipv6Bytes: ByteArray): Boolean {
            val embedded = runCatching {
                InetAddress.getByAddress(ipv6Bytes.copyOfRange(12, 16)) as? Inet4Address
            }.getOrNull() ?: return false
            return isGlobalPublicIpv4(embedded)
        }
    }
}

/**
 * CookieJar in memoria thread-safe per mantenere la sessione Laravel (`XSRF-TOKEN`, `animeunity_session`),
 * isolata strettamente sui domini di origine.
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
