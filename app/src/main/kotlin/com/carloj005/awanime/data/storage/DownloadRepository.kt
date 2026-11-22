package com.carloj005.awanime.data.storage

import android.content.Context
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
    context: Context,
    private val client: AnimeUnityClient,
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
        return if (f.exists() && f.length() > 0L) f else null
    }

    fun getEntry(animeId: String, isDub: Boolean, episodeNum: String): DownloadEntry? {
        val key = makeKey(animeId, isDub, episodeNum)
        return _downloads.value.firstOrNull { it.key == key }
    }

    fun enqueueDownload(anime: AnimeItem, episode: EpisodeItem) {
        val key = makeKey(anime.id, anime.isDub, episode.number)
        if (activeJobs[key]?.isActive == true) return

        val existingFile = getLocalFileIfReady(anime.id, anime.isDub, episode.number)
        if (existingFile != null) return

        val safeDir = File(downloadsRoot, sanitizePathSegment(anime.title)).apply { mkdirs() }
        val targetFile = File(safeDir, "${sanitizePathSegment(anime.title)} Ep. ${sanitizePathSegment(episode.number)}.mp4")

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
        upsert(initial)

        val job = scope.launch {
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.temp")
            try {
                semaphore.withPermit {
                    upsert(initial.copy(status = DownloadStatus.DOWNLOADING, errorMessage = null))
                    val directUrl = client.resolveDirectDownloadUrl(anime.id, episode.videoId)

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

    fun cancelDownload(key: String) {
        activeJobs.remove(key)?.cancel()
        val entry = _downloads.value.firstOrNull { it.key == key }
        if (entry != null) {
            File("${entry.filePath}.temp").delete()
        }
        removeEntryOnly(key)
    }

    fun deleteDownload(key: String) {
        activeJobs.remove(key)?.cancel()
        val entry = _downloads.value.firstOrNull { it.key == key }
        if (entry != null) {
            File(entry.filePath).delete()
            File("${entry.filePath}.temp").delete()
            // Pulisci cartella anime se vuota
            val parent = File(entry.filePath).parentFile
            if (parent != null && parent.isDirectory && parent.listFiles().isNullOrEmpty()) {
                parent.delete()
            }
        }
        removeEntryOnly(key)
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
