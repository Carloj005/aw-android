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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdater(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val strictHttpsGitHubInterceptor = Interceptor { chain ->
        val req = chain.request()
        if (req.url.scheme != "https" || !isTrustedGitHubHost(req.url.host)) {
            throw SecurityException("Host aggiornamento non autorizzato: ${req.url.scheme}://${req.url.host}")
        }
        chain.proceed(req)
    }

    private val httpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(strictHttpsGitHubInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    /**
     * Controlla l'ultima release su GitHub (`BuildConfig.GITHUB_REPO`).
     * Restituisce `UpdateInfo` solo se esiste una versione strettamente superiore a `BuildConfig.VERSION_NAME`
     * con asset `.apk` su host GitHub fidato (e opzionale file `.sha256` per verifica integrità).
     */
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
            val changelog = (root["body"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val htmlUrl = (root["html_url"] as? JsonPrimitive)?.content.orEmpty()

            UpdateInfo(
                versionName = remoteVersion,
                tagName = tagName,
                changelog = changelog.ifBlank { "Nuova versione $tagName disponibile." },
                downloadUrl = downloadUrl,
                sha256Url = sha256Url,
                sizeBytes = sizeBytes,
                htmlUrl = htmlUrl,
            )
        }
    }

    /**
     * Scarica l'APK nella cache privata dell'app (`cacheDir/updates/`), verifica fail-closed:
     * 1) Host HTTPS fidato GitHub
     * 2) Checksum SHA-256 (obbligatorio e fail-closed se la release espone `.sha256`)
     * 3) PackageName e certificato di firma digitale coerenti con l'app installata
     */
    suspend fun downloadUpdateApk(
        info: UpdateInfo,
        onProgress: (Float, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (!isTrustedGitHubDownloadUrl(info.downloadUrl)) {
            throw SecurityException("URL aggiornamento non fidato")
        }

        val expectedSha256 = info.sha256Url?.let { fetchExpectedSha256OrThrow(it) }

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
                                val fraction = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                                onProgress(fraction, downloaded, total)
                            }
                        }
                        output.flush()
                    }
                }
                if (!isActive) throw CancellationException("Download aggiornamento annullato")

                // 1. Verifica integrità crittografica SHA-256
                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha256 != null && !MessageDigest.isEqual(
                        actualSha256.toByteArray(Charsets.US_ASCII),
                        expectedSha256.toByteArray(Charsets.US_ASCII),
                    )
                ) {
                    throw SecurityException("Verifica SHA-256 fallita: il file APK scaricato è corrotto o manomesso.")
                }

                // 2. Verifica fail-closed di packageName e certificato di firma APK
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

    private fun fetchExpectedSha256OrThrow(sha256Url: String): String {
        if (!isTrustedGitHubDownloadUrl(sha256Url)) {
            throw SecurityException("URL checksum SHA-256 non fidato.")
        }
        val req = Request.Builder()
            .url(sha256Url)
            .header("User-Agent", "AW-Anime/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw SecurityException("Impossibile scaricare il checksum SHA-256 della release (HTTP ${resp.code}).")
            }
            return parseSha256Line(resp.body?.string().orEmpty())
                ?: throw SecurityException("File checksum SHA-256 della release malformato.")
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkMatchesInstalledSigner(apkFile: File) {
        val pm = context.packageManager
        val installedDigests = getInstalledSigningDigests(pm)
        if (installedDigests.isEmpty()) {
            throw SecurityException("Impossibile verificare la firma dell'app installata. Aggiornamento bloccato.")
        }

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
        if (archiveDigests.isEmpty()) {
            throw SecurityException("L'APK scaricato non contiene una firma digitale valida. Aggiornamento bloccato.")
        }

        val matches = archiveDigests.any { it in installedDigests }
        if (!matches) {
            throw SecurityException(
                "Firma digitale dell'APK non corrispondente all'app installata. Aggiornamento bloccato per sicurezza."
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledSigningDigests(pm: PackageManager): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val pkgInfo = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return emptySet()
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

    companion object {
        private val TRUSTED_GITHUB_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        fun isTrustedGitHubHost(rawHost: String): Boolean {
            return rawHost.trim().lowercase() in TRUSTED_GITHUB_HOSTS
        }

        fun isTrustedGitHubDownloadUrl(rawUrl: String): Boolean {
            val parsed = rawUrl.trim().toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            return isTrustedGitHubHost(parsed.host)
        }

        fun parseSha256Line(raw: String): String? {
            val token = raw.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
            return token.takeIf { Regex("^[a-f0-9]{64}$").matches(it) }
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
