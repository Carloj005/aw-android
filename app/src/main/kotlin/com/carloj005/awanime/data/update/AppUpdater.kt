package com.carloj005.awanime.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.carloj005.awanime.BuildConfig
import com.carloj005.awanime.data.model.UpdateInfo
import com.carloj005.awanime.data.network.UrlSecurityPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdater(
    private val context: Context,
    sharedDispatcher: Dispatcher? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val strictHttpsGitHubInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.url.scheme != "https" || !isTrustedGitHubHost(req.url.host)) {
            throw SecurityException("Host aggiornamento non autorizzato: ${req.url.scheme}://${req.url.host}")
        }
        chain.proceed(req)
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder().apply {
        if (sharedDispatcher != null) dispatcher(sharedDispatcher)
        dns(Dns.SYSTEM)
        cookieJar(CookieJar.NO_COOKIES)
        addNetworkInterceptor(strictHttpsGitHubInterceptor)
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
        followRedirects(true)
        followSslRedirects(false)
    }.build()

    suspend fun checkForUpdate(repo: String = BuildConfig.GITHUB_REPO): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AW-Anime/${BuildConfig.VERSION_NAME}")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) {
                throw IOException("GitHub API HTTP ${resp.code}")
            }
            val root = json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject
                ?: return@withContext null
            val tagName = (root["tag_name"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val remoteVersion = tagName.removePrefix("v").removePrefix("V")
            if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) {
                return@withContext null
            }
            val assets = (root["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?: return@withContext null

            val apkAsset = assets.firstOrNull { asset ->
                val name = (asset["name"] as? JsonPrimitive)?.content.orEmpty().lowercase()
                name.endsWith(".apk")
            } ?: return@withContext null

            val downloadUrl = (apkAsset["browser_download_url"] as? JsonPrimitive)?.content
                ?.takeIf { isTrustedGitHubDownloadUrl(it) }
                ?: return@withContext null

            val sha256Asset = assets.firstOrNull { asset ->
                val name = (asset["name"] as? JsonPrimitive)?.content.orEmpty().lowercase()
                name.endsWith(".apk.sha256") || name.endsWith(".sha256")
            }
            val sha256Url = (sha256Asset?.get("browser_download_url") as? JsonPrimitive)?.content
                ?.takeIf { isTrustedGitHubDownloadUrl(it) }

            val sizeBytes = (apkAsset["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            val rawBody = (root["body"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val changelog = cleanReleaseNotes(rawBody, tagName)
            val htmlUrl = (root["html_url"] as? JsonPrimitive)?.content.orEmpty()

            UpdateInfo(
                versionName = remoteVersion,
                tagName = tagName,
                changelog = changelog,
                downloadUrl = downloadUrl,
                sha256Url = sha256Url,
                sizeBytes = sizeBytes,
                htmlUrl = htmlUrl,
            )
        }
    }

    /**
     * Downloads the release APK and enforces fail-closed security checks:
     * 1) Trusted GitHub HTTPS origin on every redirect hop
     * 2) Mandatory SHA-256 checksum verification against `.apk.sha256`
     * 3) Mandatory APK packageName and signing certificate match against installed app
     */
    suspend fun downloadUpdateApk(
        info: UpdateInfo,
        onProgress: (Float, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!isTrustedGitHubDownloadUrl(info.downloadUrl)) {
            throw SecurityException("URL aggiornamento non fidato")
        }

        val shaUrl = info.sha256Url
            ?: throw SecurityException("File checksum .sha256 assente nella release. Aggiornamento bloccato.")
        val expectedSha256 = fetchExpectedSha256(shaUrl)

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updatesDir.listFiles()?.forEach { it.delete() }

        val safeVer = info.versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(updatesDir, "aw-anime-$safeVer.apk")
        val tempFile = File(updatesDir, "aw-anime-$safeVer.apk.tmp")

        val req = Request.Builder()
            .url(info.downloadUrl)
            .header("User-Agent", "AW-Anime/${BuildConfig.VERSION_NAME}")
            .build()

        val call = httpClient.newCall(req)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Download aggiornamento fallito (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw IOException("Corpo risposta vuoto")
                val total = body.contentLength().takeIf { it > 0L } ?: info.sizeBytes
                var downloaded = 0L
                var lastEmit = 0L
                val digest = MessageDigest.getInstance("SHA-256")

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (isActive) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 120L) {
                                lastEmit = now
                                val fraction = if (total > 0L) {
                                    (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                onProgress(fraction, downloaded, total)
                            }
                        }
                        output.flush()
                    }
                }
                if (!isActive) throw CancellationException("Download aggiornamento annullato")

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                verifySha256OrThrow(actualHex = actualSha256, expectedHex = expectedSha256)
                verifyApkMatchesInstalledSigner(tempFile)

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                onProgress(1f, targetFile.length(), targetFile.length())
                targetFile
            }
        } catch (e: Throwable) {
            call.cancel()
            tempFile.delete()
            targetFile.delete()
            throw e
        }
    }

    private fun fetchExpectedSha256(sha256Url: String): String {
        if (!isTrustedGitHubDownloadUrl(sha256Url)) {
            throw SecurityException("URL checksum SHA-256 non autorizzato.")
        }
        val req = Request.Builder()
            .url(sha256Url)
            .header("User-Agent", "AW-Anime/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw SecurityException("Impossibile scaricare il checksum SHA-256 (HTTP ${resp.code}).")
            }
            return parseSha256Line(resp.body?.string().orEmpty())
                ?: throw SecurityException("Formato checksum SHA-256 non valido nella release.")
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkMatchesInstalledSigner(apkFile: File) {
        val pm = context.packageManager
        val installedDigests = getInstalledSigningDigests(pm)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: throw SecurityException("File APK scaricato non valido o illeggibile.")

        if (archiveInfo.packageName != context.packageName) {
            throw SecurityException("Package APK non corrispondente (${archiveInfo.packageName}).")
        }

        val archiveDigests = extractSigningDigests(archiveInfo)
        verifySignerDigestsOrThrow(installedDigests = installedDigests, archiveDigests = archiveDigests)
    }

    @Suppress("DEPRECATION")
    private fun getInstalledSigningDigests(pm: PackageManager): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val pkgInfo = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
            ?: return emptySet()
        return extractSigningDigests(pkgInfo)
    }

    @Suppress("DEPRECATION")
    private fun extractSigningDigests(pkgInfo: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = pkgInfo.signingInfo
            when {
                si == null -> emptyArray()
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory
            }
        } else {
            pkgInfo.signatures ?: emptyArray()
        }
        return sigs.map { sig ->
            MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun launchApkInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    data class ChangelogEntry(
        val title: String? = null,
        val text: String,
        val isSectionHeader: Boolean = false,
    )

    companion object {
        private val SHA256_HEX_REGEX = Regex("^[a-f0-9]{64}$")
        private val FULL_CHANGELOG_LINE_REGEX = Regex(
            """^\*{0,2}\s*full\s+changelog\s*\*{0,2}\s*:\s*https?://\S+\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)\]\((https?://[^)]+)\)""")
        private val BOLD_LEAD_REGEX = Regex("""^\*\*(.+?)\*\*\s*(?:[:—–-]\s*)?(.*)$""")
        private val COLON_LEAD_REGEX = Regex("""^([^:\n]{2,56}):\s+(.+)$""")
        private val INLINE_MARKDOWN_REGEX = Regex("""(\*\*|__|\*|`)""")

        private val SECTION_LABEL_REGEX = Regex(
            """^(?:[^\p{L}\p{N}\s]{1,4}\s*)?(?:novità|correzioni|miglioramenti|fix|bugfix|sicurezza|aggiornamenti|what's new|fixes)\s*:?$""",
            RegexOption.IGNORE_CASE,
        )

        fun cleanReleaseNotes(rawBody: String, tagName: String): String {
            val entries = parseChangelogEntries(rawBody, tagName)
            return entries.joinToString("\n") { entry ->
                when {
                    entry.isSectionHeader -> entry.text
                    !entry.title.isNullOrBlank() && entry.text.isNotBlank() -> "• ${entry.title}: ${entry.text}"
                    !entry.title.isNullOrBlank() -> "• ${entry.title}"
                    else -> "• ${entry.text}"
                }
            }
        }

        fun parseChangelogEntries(rawBody: String, tagName: String): List<ChangelogEntry> {
            val cleaned = mutableListOf<ChangelogEntry>()
            for (rawLine in rawBody.lines()) {
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty()) continue
                if (FULL_CHANGELOG_LINE_REGEX.matches(trimmed)) continue
                if (trimmed.equals("## What's Changed", ignoreCase = true)) continue

                val withoutLinks = MARKDOWN_LINK_REGEX.replace(trimmed, "$1")

                if (withoutLinks.startsWith("#")) {
                    val heading = INLINE_MARKDOWN_REGEX.replace(withoutLinks.trimStart('#').trim(), "").trim()
                    if (heading.isNotBlank()) {
                        cleaned.add(ChangelogEntry(text = heading, isSectionHeader = true))
                    }
                    continue
                }

                val isBullet = withoutLinks.startsWith("- ") || withoutLinks.startsWith("* ") || withoutLinks.startsWith("• ")
                if (!isBullet) {
                    val plainHeader = INLINE_MARKDOWN_REGEX.replace(withoutLinks, "").trim()
                    if (SECTION_LABEL_REGEX.matches(plainHeader)) {
                        cleaned.add(ChangelogEntry(text = plainHeader.trimEnd(':'), isSectionHeader = true))
                        continue
                    }
                }

                val bulletBody = if (isBullet) withoutLinks.drop(2).trim() else withoutLinks
                if (bulletBody.isEmpty()) continue

                val boldMatch = BOLD_LEAD_REGEX.matchEntire(bulletBody)
                if (boldMatch != null) {
                    val lead = INLINE_MARKDOWN_REGEX.replace(boldMatch.groupValues[1], "").trim().trimEnd(':')
                    val rest = INLINE_MARKDOWN_REGEX.replace(boldMatch.groupValues[2], "").trim()
                    if (lead.isNotBlank() || rest.isNotBlank()) {
                        cleaned.add(
                            ChangelogEntry(
                                title = lead.takeIf { it.isNotBlank() },
                                text = rest,
                            )
                        )
                    }
                } else {
                    val plain = INLINE_MARKDOWN_REGEX.replace(bulletBody, "").trim()
                    val colonMatch = COLON_LEAD_REGEX.matchEntire(plain)
                    if (colonMatch != null) {
                        cleaned.add(
                            ChangelogEntry(
                                title = colonMatch.groupValues[1].trim(),
                                text = colonMatch.groupValues[2].trim(),
                            )
                        )
                    } else if (plain.isNotBlank()) {
                        cleaned.add(ChangelogEntry(text = plain))
                    }
                }
            }

            if (cleaned.isEmpty()) {
                return listOf(ChangelogEntry(text = "Nuova versione $tagName disponibile con miglioramenti e correzioni."))
            }
            return cleaned
        }

        fun isTrustedGitHubHost(rawHost: String): Boolean =
            UrlSecurityPolicy.isTrustedGitHubHost(rawHost)

        fun isTrustedGitHubDownloadUrl(rawUrl: String): Boolean =
            UrlSecurityPolicy.isTrustedGitHubDownloadUrl(rawUrl)

        fun parseSha256Line(raw: String): String? {
            val token = raw.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
            return token.takeIf { SHA256_HEX_REGEX.matches(it) }
        }

        fun verifySha256OrThrow(actualHex: String, expectedHex: String?) {
            val cleanExpected = expectedHex?.trim()?.lowercase()?.takeIf { SHA256_HEX_REGEX.matches(it) }
                ?: throw SecurityException("Checksum SHA-256 atteso mancante o non valido.")
            val cleanActual = actualHex.trim().lowercase()
            val matches = MessageDigest.isEqual(
                cleanActual.toByteArray(Charsets.US_ASCII),
                cleanExpected.toByteArray(Charsets.US_ASCII),
            )
            if (!matches) {
                throw SecurityException("Verifica SHA-256 fallita: il file APK scaricato è corrotto o manomesso.")
            }
        }

        fun verifySignerDigestsOrThrow(installedDigests: Set<String>, archiveDigests: Set<String>) {
            if (installedDigests.isEmpty() || archiveDigests.isEmpty()) {
                throw SecurityException("Impossibile verificare il certificato di firma digitale dell'APK.")
            }
            if (archiveDigests.none { it in installedDigests }) {
                throw SecurityException(
                    "Firma digitale dell'APK non corrispondente all'app installata. Aggiornamento bloccato per sicurezza."
                )
            }
        }

        fun isNewerVersion(remote: String, current: String): Boolean {
            val rParts = parseSemVer(remote)
            val cParts = parseSemVer(current)
            val maxLen = maxOf(rParts.size, cParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val c = cParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseSemVer(v: String): List<Int> {
            return v.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore("-")
                .split(".")
                .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        }
    }
}
