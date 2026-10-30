package com.bilimusicplayer.download

import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.Song
import kotlinx.coroutines.flow.Flow

interface DownloadService {
    fun observeAll(): Flow<List<Download>>
    fun observe(songId: String): Flow<Download?>
    fun activeCount(): Flow<Int>

    suspend fun startDownload(song: Song, audioUrl: String)
    suspend fun cancel(songId: String)
    suspend fun pause(songId: String)
    suspend fun resume(songId: String)
    suspend fun delete(songId: String)
    suspend fun clearCompleted()
    suspend fun clearFailed()
    suspend fun updateConcurrencyLimit(max: Int)
}
