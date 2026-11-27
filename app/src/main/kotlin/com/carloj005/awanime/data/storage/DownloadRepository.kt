package com.carloj005.awanime.data.storage

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.carloj005.awanime.data.model.AnimeItem
import com.carloj005.awanime.data.model.DownloadEntry
import com.carloj005.awanime.data.model.DownloadStatus
import com.carloj005.awanime.data.model.EpisodeItem
import com.carloj005.awanime.data.network.AnimeUnityClient
import com.carloj005.awanime.data.network.NetworkMonitor
import com.carloj005.awanime.data.network.UrlSecurityPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class DownloadRepository(
    private val context: Context,
    private val client: AnimeUnityClient,
    private val downloadHttpClient: OkHttpClient,
    private val settingsRepo: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val BUFFER_BYTES = 128 * 1024
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
        private const val MAX_CONCURRENT_DOWNLOADS = 2

        fun uniqueWorkName(key: String): String = "aw_download_$key"
    }

    private val downloadsRoot: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
        "AwAnime",
    )

    private val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    private val store = JsonFileStore(
        file = File(context.filesDir, "downloads.json"),
        serializer = ListSerializer(DownloadEntry.serializer()),
        defaultValue = emptyList(),
        ioDispatcher = ioDispatcher,
        normalizeLoaded = { list ->
            var changed = false
            val normalized = list.sortedByDescending { it.updatedAt }.map { entry ->
                when {
                    entry.status == DownloadStatus.COMPLETED && !File(entry.filePath).exists() -> {
                        changed = true
                        entry.copy(status = DownloadStatus.FAILED, errorMessage = "File locale rimosso")
                    }
                    entry.status == DownloadStatus.DOWNLOADING || entry.status == DownloadStatus.QUEUED -> {
                        changed = true
                        val partialBytes = File("${entry.filePath}.temp").takeIf { it.exists() }?.length()
                            ?: entry.bytesDownloaded
                        entry.copy(
                            status = DownloadStatus.FAILED,
                            bytesDownloaded = partialBytes,
                            errorMessage = "Download interrotto (tocca Riprova per riprendere)",
                        )
                    }
                    else -> entry
                }
            }
            normalized to changed
        },
    )

    val downloads: StateFlow<List<DownloadEntry>> = store.state

    fun makeKey(animeId: String, isDub: Boolean, episodeNum: String): String {
        return "${animeId}_${if (isDub) "ita" else "sub"}_$episodeNum"
    }

    fun getLocalFileIfReady(animeId: String, isDub: Boolean, episodeNum: String): File? {
        val key = makeKey(animeId, isDub, episodeNum)
        val entry = store.value.firstOrNull { it.key == key } ?: return null
        if (entry.status != DownloadStatus.COMPLETED) return null
        val file = File(entry.filePath)
        return if (isInsideDownloadsRoot(file) && file.exists() && file.length() > 0L) file else null
    }

    fun getEntryByKey(key: String): DownloadEntry? {
        return store.value.firstOrNull { it.key == key }
    }

    fun enqueueDownload(anime: AnimeItem, episode: EpisodeItem) {
        val key = makeKey(anime.id, anime.isDub, episode.number)
        val existing = getEntryByKey(key)
        if (existing?.status == DownloadStatus.DOWNLOADING || existing?.status == DownloadStatus.QUEUED) {
            return
        }
        if (getLocalFileIfReady(anime.id, anime.isDub, episode.number) != null) {
            return
        }

        val safeDir = File(downloadsRoot, sanitizePathSegment(anime.title))
        val targetFile = File(
            safeDir,
            "${sanitizePathSegment(anime.title)} Ep. ${sanitizePathSegment(episode.number)}.mp4",
        )
        if (!isInsideDownloadsRoot(targetFile)) return

        val existingTempBytes = File("${targetFile.absolutePath}.temp").takeIf { it.exists() }?.length() ?: 0L
        val wifiOnly = settingsRepo.settings.value.wifiOnlyDownloads

        val initial = DownloadEntry(
            key = key,
            animeId = anime.id,
            animeTitle = anime.title,
            posterUrl = anime.displayImageUrl,
            isDub = anime.isDub,
            episodeNum = episode.number,
            videoId = episode.videoId,
            filePath = targetFile.absolutePath,
            bytesDownloaded = existingTempBytes,
            totalBytes = existing?.totalBytes ?: 0L,
            status = DownloadStatus.QUEUED,
            updatedAt = System.currentTimeMillis(),
        )

        if (wifiOnly && !networkMonitor.isUnmeteredWifiConnected()) {
            upsert(
                initial.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "Bloccato: opzione 'Download solo su Wi-Fi' attiva nelle Impostazioni.",
                )
            )
            return
        }

        upsert(initial)
        scheduleWorker(key = key, wifiOnly = wifiOnly)
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
        workManager.cancelUniqueWork(uniqueWorkName(key))
        val entry = getEntryByKey(key)
        removeEntryOnly(key)
        if (entry != null) {
            deleteTempFileSafely(entry.filePath)
        }
    }

    fun deleteDownload(key: String) {
        workManager.cancelUniqueWork(uniqueWorkName(key))
        val entry = getEntryByKey(key)
        removeEntryOnly(key)
        if (entry != null) {
            deleteFilesSafely(entry.filePath)
        }
    }

    internal suspend fun executeScheduledDownload(
        key: String,
        onProgressUpdate: suspend (DownloadEntry) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val initial = store.awaitLoaded().firstOrNull { it.key == key } ?: return@withContext true
        if (initial.status == DownloadStatus.COMPLETED) return@withContext true

        val targetFile = File(initial.filePath)
        if (!isInsideDownloadsRoot(targetFile)) {
            removeEntryOnly(key)
            return@withContext false
        }
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.temp")

        try {
            semaphore.withPermit {
                if (settingsRepo.settings.value.wifiOnlyDownloads && !networkMonitor.isUnmeteredWifiConnected()) {
                    throw IllegalStateException("Connessione Wi-Fi richiesta (opzione attiva nelle Impostazioni)")
                }

                val resumeBytes = if (tempFile.exists()) tempFile.length().coerceAtLeast(0L) else 0L
                val activeStart = initial.copy(
                    status = DownloadStatus.DOWNLOADING,
                    bytesDownloaded = resumeBytes,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                )
                upsert(activeStart)
                onProgressUpdate(activeStart)

                val source = client.resolveDirectDownload(initial.animeId, initial.videoId)
                if (!UrlSecurityPolicy.isTrustedMediaUrl(source.url)) {
                    throw SecurityException("URL download non HTTPS o non sicuro bloccato")
                }

                val reqBuilder = Request.Builder()
                    .url(source.url)
                    .header("User-Agent", AnimeUnityClient.USER_AGENT)
                    .header("Referer", source.referer)
                if (resumeBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$resumeBytes-")
                }

                downloadHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code == 416 && resumeBytes > 0L) {
                        finalizeCompletedFile(tempFile, targetFile, initial)
                        return@withPermit
                    }
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Errore server download HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Risposta download vuota")
                    val isPartial = resp.code == 206 && resumeBytes > 0L
                    val startOffset = if (isPartial) resumeBytes else 0L
                    val contentLength = body.contentLength().coerceAtLeast(0L)
                    val totalBytes = if (contentLength > 0L) startOffset + contentLength else 0L

                    var downloaded = startOffset
                    var lastEmit = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile, isPartial).use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (currentCoroutineContext().isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmit >= PROGRESS_EMIT_INTERVAL_MS) {
                                    lastEmit = now
                                    val progressEntry = initial.copy(
                                        status = DownloadStatus.DOWNLOADING,
                                        bytesDownloaded = downloaded,
                                        totalBytes = totalBytes,
                                        updatedAt = now,
                                    )
                                    upsert(progressEntry)
                                    onProgressUpdate(progressEntry)
                                }
                            }
                            output.flush()
                        }
                    }

                    if (!currentCoroutineContext().isActive) {
                        throw CancellationException("Download annullato")
                    }
                    finalizeCompletedFile(tempFile, targetFile, initial)
                }
            }
            true
        } catch (ce: CancellationException) {
            // If user cancelled/deleted, the entry was already removed; clean up temp file.
            if (getEntryByKey(key) == null) {
                tempFile.delete()
            } else {
                val savedBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
                upsert(
                    initial.copy(
                        status = DownloadStatus.FAILED,
                        bytesDownloaded = savedBytes,
                        errorMessage = "Download messo in pausa/interrotto",
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
            throw ce
        } catch (e: Exception) {
            val savedBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
            upsert(
                initial.copy(
                    status = DownloadStatus.FAILED,
                    bytesDownloaded = savedBytes,
                    errorMessage = e.message ?: "Errore durante il download",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            false
        }
    }

    private fun finalizeCompletedFile(tempFile: File, targetFile: File, initial: DownloadEntry) {
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        val finalSize = targetFile.length()
        upsert(
            initial.copy(
                status = DownloadStatus.COMPLETED,
                bytesDownloaded = finalSize,
                totalBytes = finalSize,
                errorMessage = null,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun scheduleWorker(key: String, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(DownloadWorker.KEY_DOWNLOAD_ID to key))
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(key),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun deleteTempFileSafely(filePath: String) {
        val tmp = File("$filePath.temp")
        if (isInsideDownloadsRoot(tmp)) tmp.delete()
    }

    private fun deleteFilesSafely(filePath: String) {
        val file = File(filePath)
        val tmp = File("$filePath.temp")
        if (isInsideDownloadsRoot(file)) {
            file.delete()
            val parent = file.parentFile
            if (parent != null && isInsideDownloadsRoot(parent) && parent.isDirectory && parent.listFiles().isNullOrEmpty()) {
                parent.delete()
            }
        }
        if (isInsideDownloadsRoot(tmp)) tmp.delete()
    }

    private fun isInsideDownloadsRoot(file: File): Boolean {
        return runCatching {
            downloadsRoot.mkdirs()
            val rootCanon = downloadsRoot.canonicalPath
            val targetCanon = file.canonicalPath
            targetCanon == rootCanon || targetCanon.startsWith("$rootCanon${File.separator}")
        }.getOrDefault(false)
    }

    private fun upsert(entry: DownloadEntry) {
        store.update { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.key == entry.key }
            if (idx >= 0) {
                list[idx] = entry
            } else {
                list.add(0, entry)
            }
            list
        }
    }

    private fun removeEntryOnly(key: String) {
        store.update { current -> current.filterNot { it.key == key } }
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
