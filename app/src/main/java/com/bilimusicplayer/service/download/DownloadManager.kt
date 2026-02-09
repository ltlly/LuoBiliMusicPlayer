package com.bilimusicplayer.service.download

import android.content.Context
import androidx.work.*
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.DownloadStatus
import com.bilimusicplayer.data.model.Song
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * Manager for handling audio downloads
 */
class DownloadManager(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val downloadDao = database.downloadDao()
    private val workManager = WorkManager.getInstance(context)

    /**
     * Start download for a song
     */
    suspend fun startDownload(song: Song, audioUrl: String) {
        // Create download record
        val download = Download(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            status = DownloadStatus.QUEUED,
            audioUrl = audioUrl,
            totalBytes = 0
        )
        downloadDao.insertDownload(download)

        // Create work request
        val workData = workDataOf(
            AudioDownloadWorker.KEY_SONG_ID to song.id,
            AudioDownloadWorker.KEY_AUDIO_URL to audioUrl,
            AudioDownloadWorker.KEY_TITLE to song.title,
            AudioDownloadWorker.KEY_ARTIST to song.artist
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
            .addTag(song.id)
            .build()

        workManager.enqueueUniqueWork(
            "download_${song.id}",
            ExistingWorkPolicy.KEEP,
            downloadRequest
        )
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
            // Recreate work request
            val workData = workDataOf(
                AudioDownloadWorker.KEY_SONG_ID to songId,
                AudioDownloadWorker.KEY_AUDIO_URL to download.audioUrl,
                AudioDownloadWorker.KEY_TITLE to download.title,
                AudioDownloadWorker.KEY_ARTIST to download.artist
            )

            val downloadRequest = OneTimeWorkRequestBuilder<AudioDownloadWorker>()
                .setInputData(workData)
                .addTag(TAG_DOWNLOAD)
                .addTag(songId)
                .build()

            workManager.enqueueUniqueWork(
                "download_$songId",
                ExistingWorkPolicy.REPLACE,
                downloadRequest
            )

            downloadDao.updateDownloadStatus(songId, DownloadStatus.QUEUED)
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
        private const val TAG_DOWNLOAD = "download"
    }
}
