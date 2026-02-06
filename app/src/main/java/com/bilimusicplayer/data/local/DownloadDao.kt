package com.bilimusicplayer.data.local

import androidx.room.*
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Download operations
 */
@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY startedAt DESC")
    fun getAllDownloads(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY startedAt DESC")
    fun getDownloadsByStatus(status: DownloadStatus): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE songId = :songId")
    suspend fun getDownloadBySongId(songId: String): Download?

    @Query("SELECT * FROM downloads WHERE songId = :songId")
    fun getDownloadBySongIdFlow(songId: String): Flow<Download?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: Download)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloads(downloads: List<Download>)

    @Update
    suspend fun updateDownload(download: Download)

    @Delete
    suspend fun deleteDownload(download: Download)

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun deleteDownloadBySongId(songId: String)

    @Query("UPDATE downloads SET status = :status WHERE songId = :songId")
    suspend fun updateDownloadStatus(songId: String, status: DownloadStatus)

    @Query("UPDATE downloads SET progress = :progress, downloadedBytes = :downloadedBytes WHERE songId = :songId")
    suspend fun updateDownloadProgress(songId: String, progress: Int, downloadedBytes: Long)

    @Query("UPDATE downloads SET status = :status, localPath = :localPath, completedAt = :completedAt WHERE songId = :songId")
    suspend fun completeDownload(songId: String, status: DownloadStatus, localPath: String?, completedAt: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = :status, errorMessage = :errorMessage WHERE songId = :songId")
    suspend fun failDownload(songId: String, status: DownloadStatus, errorMessage: String)

    @Query("DELETE FROM downloads WHERE status = :status")
    suspend fun deleteDownloadsByStatus(status: DownloadStatus)

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN (:statuses)")
    fun getDownloadCountByStatuses(statuses: List<DownloadStatus>): Flow<Int>
}
