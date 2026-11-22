package com.carloj005.awanime.data.storage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.network.AnimeUnityClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class DownloadRepository(
    private val context: Context,
    private val client: AnimeUnityClient,
    private val settingsRepo: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val metaFile = File(context.filesDir, "downloads.json")
    private val downloadsRoot: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
        "AwAnime",
    ).apply { mkdirs() }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val semaphore = Semaphore(2)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    init {
        val loaded = readFromDisk().map { entry ->
            when {
                entry.status == DownloadStatus.COMPLETED && !File(entry.filePath).exists() -> {
                    entry.copy(status = DownloadStatus.FAILED, errorMessage = "File locale rimosso")
                }
                entry.status == DownloadStatus.DOWNLOADING || entry.status == DownloadStatus.QUEUED -> {
                    entry.copy(status = DownloadStatus.FAILED, errorMessage = "Download interrotto")
                }
                else -> entry
            }
        }
        _downloads.value = loaded
        writeToDisk(loaded)
    }

    fun makeKey(animeId: String, isDub: Boolean, episodeNum: String): String {
        return "${animeId}_${if (isDub) "ita" else "sub"}_$episodeNum"
    }

    fun getLocalFileIfReady(animeId: String, isDub: Boolean, episodeNum: String): File? {
        val key = makeKey(animeId, isDub, episodeNum)
        val entry = _downloads.value.firstOrNull { it.key == key } ?: return null
        if (entry.status != DownloadStatus.COMPLETED) return null
        val f = File(entry.filePath)
        return if (isInsideDownloadsRoot(f) && f.exists() && f.length() > 0L) f else null
    }

    fun getEntry(animeId: String, isDub: Boolean, episodeNum: String): DownloadEntry? {
        val key = makeKey(animeId, isDub, episodeNum)
        return _downloads.value.firstOrNull { it.key == key }
    }

    fun isUnmeteredWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun enqueueDownload(anime: AnimeItem, episode: EpisodeItem) {
        val key = makeKey(anime.id, anime.isDub, episode.number)
        if (activeJobs[key]?.isActive == true) return

        val existingFile = getLocalFileIfReady(anime.id, anime.isDub, episode.number)
        if (existingFile != null) return

        val safeDir = File(downloadsRoot, sanitizePathSegment(anime.title)).apply { mkdirs() }
        val targetFile = File(safeDir, "${sanitizePathSegment(anime.title)} Ep. ${sanitizePathSegment(episode.number)}.mp4")
        if (!isInsideDownloadsRoot(targetFile)) {
            return
        }

        val initial = DownloadEntry(
            key = key,
            animeId = anime.id,
            animeTitle = anime.title,
            posterUrl = anime.displayImageUrl,
            isDub = anime.isDub,
            episodeNum = episode.number,
            videoId = episode.videoId,
            filePath = targetFile.absolutePath,
            status = DownloadStatus.QUEUED,
            updatedAt = System.currentTimeMillis(),
        )

        if (settingsRepo.settings.value.wifiOnlyDownloads && !isUnmeteredWifiConnected()) {
            upsert(
                initial.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "Bloccato: opzione 'Download solo su Wi-Fi' attiva nelle Impostazioni.",
                ),
                persist = true,
            )
            return
        }

        upsert(initial)

        val job = scope.launch {
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.temp")
            try {
                semaphore.withPermit {
                    if (settingsRepo.settings.value.wifiOnlyDownloads && !isUnmeteredWifiConnected()) {
                        throw IllegalStateException("Connessione Wi-Fi richiesta (opzione attiva nelle Impostazioni)")
                    }
                    upsert(initial.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null))
                    val directUrl = client.resolveDirectDownloadUrl(anime.id, episode.videoId)
                    if (!directUrl.startsWith("https://")) {
                        throw SecurityException("URL download non HTTPS bloccato per sicurezza")
                    }

                    val req = Request.Builder()
                        .url(directUrl)
                        .header("User-Agent", AnimeUnityClient.USER_AGENT)
                        .header("Referer", "https://vixcloud.cc/")
                        .build()

                    client.mediaOkHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("Errore server download HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw IllegalStateException("Risposta download vuota")
                        val totalBytes = body.contentLength().coerceAtLeast(0L)
                        var downloaded = 0L
                        var lastUiUpdate = 0L

                        body.byteStream().use { input ->
                            FileOutputStream(tempFile).use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (isActive) {
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastUiUpdate >= 250L) {
                                        lastUiUpdate = now
                                        upsert(
                                            initial.copy(
                                                status = DownloadStatus.DOWNLOADING,
                                                bytesDownloaded = downloaded,
                                                totalBytes = totalBytes,
                                                updatedAt = now,
                                            ),
                                            persist = false,
                                        )
                                    }
                                }
                                output.flush()
                            }
                        }
                        if (!isActive) throw CancellationException("Download annullato")
                        if (targetFile.exists()) targetFile.delete()
                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                            tempFile.delete()
                        }
                        upsert(
                            initial.copy(
                                status = DownloadStatus.COMPLETED,
                                bytesDownloaded = targetFile.length(),
                                totalBytes = targetFile.length(),
                                updatedAt = System.currentTimeMillis(),
                            ),
                            persist = true,
                        )
                    }
                }
            } catch (_: CancellationException) {
                tempFile.delete()
                removeEntryOnly(key)
            } catch (e: Exception) {
                tempFile.delete()
                upsert(
                    initial.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.message ?: "Errore durante il download",
                        updatedAt = System.currentTimeMillis(),
                    ),
                    persist = true,
                )
            } finally {
                activeJobs.remove(key)
            }
        }
        activeJobs[key] = job
    }

    fun retryDownload(entry: DownloadEntry) {
        val anime = AnimeItem(
            id = entry.animeId,
            title = entry.animeTitle,
            posterUrl = entry.posterUrl,
            isDub = entry.isDub,
            audioLabel = if (entry.isDub) "Italiano" else "Giapponese",
        )
        val ep = EpisodeItem(
            number = entry.episodeNum,
            videoId = entry.videoId,
            language = if (entry.isDub) "ita" else "jpn",
        )
        enqueueDownload(anime, ep)
    }

    fun cancelDownload(key: String) {
        activeJobs.remove(key)?.cancel()
        val entry = _downloads.value.firstOrNull { it.key == key }
        if (entry != null) {
            val tmp = File("${entry.filePath}.temp")
            if (isInsideDownloadsRoot(tmp)) tmp.delete()
        }
        removeEntryOnly(key)
    }

    fun deleteDownload(key: String) {
        activeJobs.remove(key)?.cancel()
        val entry = _downloads.value.firstOrNull { it.key == key }
        if (entry != null) {
            val f = File(entry.filePath)
            val tmp = File("${entry.filePath}.temp")
            if (isInsideDownloadsRoot(f)) {
                f.delete()
                val parent = f.parentFile
                if (parent != null && isInsideDownloadsRoot(parent) && parent.isDirectory && parent.listFiles().isNullOrEmpty()) {
                    parent.delete()
                }
            }
            if (isInsideDownloadsRoot(tmp)) tmp.delete()
        }
        removeEntryOnly(key)
    }

    private fun isInsideDownloadsRoot(file: File): Boolean {
        return runCatching {
            val rootCanon = downloadsRoot.canonicalPath
            val targetCanon = file.canonicalPath
            targetCanon == rootCanon || targetCanon.startsWith("$rootCanon${File.separator}")
        }.getOrDefault(false)
    }

    private fun upsert(entry: DownloadEntry, persist: Boolean = true) {
        val next = synchronized(lock) {
            val list = _downloads.value.toMutableList()
            val idx = list.indexOfFirst { it.key == entry.key }
            if (idx >= 0) {
                list[idx] = entry
            } else {
                list.add(0, entry)
            }
            if (persist) writeToDisk(list)
            list.toList()
        }
        _downloads.value = next
    }

    private fun removeEntryOnly(key: String) {
        val next = synchronized(lock) {
            val list = _downloads.value.filterNot { it.key == key }
            writeToDisk(list)
            list
        }
        _downloads.value = next
    }

    private fun readFromDisk(): List<DownloadEntry> = synchronized(lock) {
        if (!metaFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<DownloadEntry>>(metaFile.readText(Charsets.UTF_8))
                .sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    private fun writeToDisk(list: List<DownloadEntry>) = synchronized(lock) {
        val payload = json.encodeToString(list)
        val tmp = File(metaFile.parentFile, "${metaFile.name}.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(metaFile)) {
            metaFile.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun sanitizePathSegment(raw: String): String {
        val cleaned = raw
            .replace(Regex("""["*/:<>?\\|]"""), " ")
            .replace("..", ".")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
        return cleaned.ifEmpty { "Anime" }
    }
}
