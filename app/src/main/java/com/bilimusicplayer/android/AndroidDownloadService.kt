package com.bilimusicplayer.android

import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.download.DownloadService
import com.bilimusicplayer.service.download.DownloadManager
import kotlinx.coroutines.flow.Flow

class AndroidDownloadService(private val manager: DownloadManager) : DownloadService {
    override fun observeAll(): Flow<List<Download>> = manager.getAllDownloads()
    override fun observe(songId: String): Flow<Download?> = manager.getDownload(songId)
    override fun activeCount(): Flow<Int> = manager.getActiveDownloadCount()
    override suspend fun startDownload(song: Song, audioUrl: String) = manager.startDownload(song, audioUrl)
    override suspend fun cancel(songId: String) = manager.cancelDownload(songId)
    override suspend fun pause(songId: String) = manager.pauseDownload(songId)
    override suspend fun resume(songId: String) = manager.resumeDownload(songId)
    override suspend fun delete(songId: String) = manager.deleteDownload(songId)
    override suspend fun clearCompleted() = manager.clearCompletedDownloads()
    override suspend fun clearFailed() = manager.clearFailedDownloads()
    override suspend fun updateConcurrencyLimit(max: Int) = manager.updateConcurrencyLimit(max)
}
