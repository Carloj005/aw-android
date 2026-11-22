package com.carloj005.awanime.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.carloj005.awanime.BuildConfig
import com.carloj005.awanime.data.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppUpdater(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Controlla l'ultima release su GitHub (`BuildConfig.GITHUB_REPO`).
     * Restituisce `UpdateInfo` solo se esiste una versione strettamente superiore a `BuildConfig.VERSION_NAME`.
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
            val assets = root["assets"] as? JsonArray ?: return@withContext null
            val apkAsset = assets.mapNotNull { it as? JsonObject }.firstOrNull { asset ->
                val name = (asset["name"] as? JsonPrimitive)?.content.orEmpty().lowercase()
                name.endsWith(".apk")
            } ?: return@withContext null

            val downloadUrl = (apkAsset["browser_download_url"] as? JsonPrimitive)?.content
                ?.takeIf { it.startsWith("https://") }
                ?: return@withContext null
            val sizeBytes = (apkAsset["size"] as? JsonPrimitive)?.longOrNull ?: 0L
            val changelog = (root["body"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val htmlUrl = (root["html_url"] as? JsonPrimitive)?.content.orEmpty()

            UpdateInfo(
                versionName = remoteVersion,
                tagName = tagName,
                changelog = changelog.ifBlank { "Nuova versione $tagName disponibile." },
                downloadUrl = downloadUrl,
                sizeBytes = sizeBytes,
                htmlUrl = htmlUrl,
            )
        }
    }

    /**
     * Scarica l'APK nella cache privata dell'app (`cacheDir/updates/`) notificando il progresso [0f..1f].
     */
    suspend fun downloadUpdateApk(
        info: UpdateInfo,
        onProgress: (Float, Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Rimuove eventuali APK vecchi dalla cache
        updatesDir.listFiles()?.forEach { it.delete() }

        val targetFile = File(updatesDir, "aw-anime-${info.versionName}.apk")
        val tempFile = File(updatesDir, "aw-anime-${info.versionName}.apk.tmp")

        val req = Request.Builder()
            .url(info.downloadUrl)
            .header("User-Agent", "AW-Anime/${BuildConfig.VERSION_NAME}")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Download aggiornamento fallito (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw IOException("Corpo risposta vuoto")
            val total = body.contentLength().takeIf { it > 0L } ?: info.sizeBytes
            var downloaded = 0L
            var lastEmit = 0L

            try {
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
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
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                onProgress(1f, targetFile.length(), targetFile.length())
                targetFile
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
        }
    }

    /**
     * Verifica se l'app ha il permesso "Installa app sconosciute".
     */
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

    /**
     * Avvia l'installatore nativo Android sull'APK scaricato tramite FileProvider.
     */
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
