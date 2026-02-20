package com.bilimusicplayer.service.download

import android.content.Context
import android.util.Log
import androidx.work.*
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.DownloadStatus
import com.bilimusicplayer.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Manager for handling audio downloads.
 * Uses a Semaphore to limit actual concurrent download Workers to the configured max.
 * Workers are dispatched sequentially through a coroutine that acquires permits.
 */
class DownloadManager(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val downloadDao = database.downloadDao()
    private val workManager = WorkManager.getInstance(context)
    private val settingsManager = DownloadSettingsManager(context)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Semaphore to limit concurrent actual downloads
    @Volatile
    private var downloadSemaphore: Semaphore? = null
    private val semaphoreMutex = Mutex()

    private suspend fun getSemaphore(): Semaphore {
        // Lazy init, always uses latest setting
        val existing = downloadSemaphore
        if (existing != null) return existing
        return semaphoreMutex.withLock {
            downloadSemaphore ?: run {
                val max = settingsManager.getMaxConcurrentDownloads()
                Log.d(TAG, "初始化下载信号量，最大并发: $max")
                Semaphore(max).also { downloadSemaphore = it }
            }
        }
    }

    /**
     * Call when the concurrent download setting changes at runtime.
     * Creates a new semaphore with the new limit.
     * Existing in-flight downloads will finish; new ones use the new limit.
     */
    suspend fun updateConcurrencyLimit(newMax: Int) {
        semaphoreMutex.withLock {
            Log.d(TAG, "更新并发限制: $newMax")
            downloadSemaphore = Semaphore(newMax)
        }
    }

    /**
     * Start download for a song.
     * The actual WorkManager enqueue is gated by a semaphore so at most N downloads run concurrently.
     */
    suspend fun startDownload(song: Song, audioUrl: String) {
        // Create download record immediately so it shows up in UI
        val download = Download(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            status = DownloadStatus.QUEUED,
            audioUrl = audioUrl,
            totalBytes = 0
        )
        downloadDao.insertDownload(download)

        // Launch the actual download in a background scope, gated by semaphore
        managerScope.launch {
            val sem = getSemaphore()
            sem.withPermit {
                // Re-check status in case it was cancelled while waiting
                val current = downloadDao.getDownloadBySongId(song.id)
                if (current == null || current.status == DownloadStatus.CANCELLED || current.status == DownloadStatus.PAUSED) {
                    Log.d(TAG, "跳过已取消/暂停的下载: ${song.title}")
                    return@withPermit
                }

                enqueueWorker(song.id, audioUrl, song.title, song.artist)

                // Wait for the worker to complete before releasing the permit
                waitForWorkerCompletion("download_${song.id}")
            }
        }
    }

    /**
     * Actually enqueue a WorkManager worker
     */
    private fun enqueueWorker(songId: String, audioUrl: String, title: String, artist: String) {
        val workData = workDataOf(
            AudioDownloadWorker.KEY_SONG_ID to songId,
            AudioDownloadWorker.KEY_AUDIO_URL to audioUrl,
            AudioDownloadWorker.KEY_TITLE to title,
            AudioDownloadWorker.KEY_ARTIST to artist
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<AudioDownloadWorker>()
            .setInputData(workData)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(TAG_DOWNLOAD)
            .addTag(songId)
            .build()

        workManager.enqueueUniqueWork(
            "download_$songId",
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )
    }

    /**
     * Polls the download record until the worker finishes (COMPLETED / FAILED / CANCELLED).
     * This keeps the semaphore permit held while the worker is active.
     */
    private suspend fun waitForWorkerCompletion(uniqueWorkName: String) {
        val songId = uniqueWorkName.removePrefix("download_")
        while (true) {
            kotlinx.coroutines.delay(2000) // Check every 2 seconds
            val download = downloadDao.getDownloadBySongId(songId) ?: break
            when (download.status) {
                DownloadStatus.COMPLETED,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED,
                DownloadStatus.PAUSED -> break
                else -> continue
            }
        }
    }

    /**
     * Cancel download
     */
    suspend fun cancelDownload(songId: String) {
        workManager.cancelAllWorkByTag(songId)
        downloadDao.updateDownloadStatus(songId, DownloadStatus.CANCELLED)
    }

    /**
     * Pause download
     */
    suspend fun pauseDownload(songId: String) {
        workManager.cancelAllWorkByTag(songId)
        downloadDao.updateDownloadStatus(songId, DownloadStatus.PAUSED)
    }

    /**
     * Resume download
     */
    suspend fun resumeDownload(songId: String) {
        val download = downloadDao.getDownloadBySongId(songId)
        if (download != null && download.status == DownloadStatus.PAUSED) {
            downloadDao.updateDownloadStatus(songId, DownloadStatus.QUEUED)

            // Re-enqueue via the semaphore-gated path
            managerScope.launch {
                val sem = getSemaphore()
                sem.withPermit {
                    val current = downloadDao.getDownloadBySongId(songId)
                    if (current == null || current.status == DownloadStatus.CANCELLED) return@withPermit

                    enqueueWorker(songId, download.audioUrl, download.title, download.artist)
                    waitForWorkerCompletion("download_$songId")
                }
            }
        }
    }

    /**
     * Get all downloads
     */
    fun getAllDownloads(): Flow<List<Download>> {
        return downloadDao.getAllDownloads()
    }

    /**
     * Get downloads by status
     */
    fun getDownloadsByStatus(status: DownloadStatus): Flow<List<Download>> {
        return downloadDao.getDownloadsByStatus(status)
    }

    /**
     * Get download for specific song
     */
    fun getDownload(songId: String): Flow<Download?> {
        return downloadDao.getDownloadBySongIdFlow(songId)
    }

    /**
     * Delete download record
     */
    suspend fun deleteDownload(songId: String) {
        downloadDao.deleteDownloadBySongId(songId)
    }

    /**
     * Clear completed downloads
     */
    suspend fun clearCompletedDownloads() {
        downloadDao.deleteDownloadsByStatus(DownloadStatus.COMPLETED)
    }

    /**
     * Clear failed downloads
     */
    suspend fun clearFailedDownloads() {
        downloadDao.deleteDownloadsByStatus(DownloadStatus.FAILED)
    }

    /**
     * Get active download count
     */
    fun getActiveDownloadCount(): Flow<Int> {
        return downloadDao.getDownloadCountByStatuses(
            listOf(
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.CONVERTING
            )
        )
    }

    companion object {
        private const val TAG = "DownloadManager"
        private const val TAG_DOWNLOAD = "download"
    }
}
