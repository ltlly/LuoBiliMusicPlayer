package com.bilimusicplayer.data.local

import androidx.room.*
import com.bilimusicplayer.data.model.CachedFavoriteMedia

/**
 * Data Access Object for cached favorite media content
 */
@Dao
interface CachedFavoriteMediaDao {

    @Query("SELECT * FROM cached_favorite_medias WHERE folderId = :folderId ORDER BY position ASC")
    suspend fun getByFolderId(folderId: Long): List<CachedFavoriteMedia>

    @Query("SELECT COUNT(*) FROM cached_favorite_medias WHERE folderId = :folderId")
    suspend fun getCountByFolderId(folderId: Long): Int

    @Query("SELECT MIN(cachedAt) FROM cached_favorite_medias WHERE folderId = :folderId")
    suspend fun getCacheTimestamp(folderId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedFavoriteMedia>)

    @Query("DELETE FROM cached_favorite_medias WHERE folderId = :folderId")
    suspend fun deleteByFolderId(folderId: Long)

    @Query("DELETE FROM cached_favorite_medias")
    suspend fun deleteAll()
}

