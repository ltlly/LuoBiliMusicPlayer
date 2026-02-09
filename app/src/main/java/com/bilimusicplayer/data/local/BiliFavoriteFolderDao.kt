package com.bilimusicplayer.data.local

import androidx.room.*
import com.bilimusicplayer.data.model.BiliFavoriteFolder
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Bilibili Favorite Folder cache
 */
@Dao
interface BiliFavoriteFolderDao {

    @Query("SELECT * FROM bili_favorite_folders ORDER BY updatedAt DESC")
    fun getAllFolders(): Flow<List<BiliFavoriteFolder>>

    @Query("SELECT * FROM bili_favorite_folders ORDER BY updatedAt DESC")
    suspend fun getAllFoldersOnce(): List<BiliFavoriteFolder>

    @Query("SELECT * FROM bili_favorite_folders WHERE id = :folderId")
    suspend fun getFolderById(folderId: Long): BiliFavoriteFolder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: BiliFavoriteFolder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<BiliFavoriteFolder>)

    @Update
    suspend fun updateFolder(folder: BiliFavoriteFolder)

    @Delete
    suspend fun deleteFolder(folder: BiliFavoriteFolder)

    @Query("DELETE FROM bili_favorite_folders")
    suspend fun deleteAllFolders()

    @Query("SELECT COUNT(*) FROM bili_favorite_folders")
    suspend fun getFolderCount(): Int

    @Query("SELECT MAX(updatedAt) FROM bili_favorite_folders")
    suspend fun getLastUpdateTime(): Long?

    /**
     * Check if cache is stale (older than specified time)
     */
    suspend fun isCacheStale(maxAgeMs: Long = 5 * 60 * 1000): Boolean {
        val lastUpdate = getLastUpdateTime() ?: 0
        return (System.currentTimeMillis() - lastUpdate) > maxAgeMs
    }
}
