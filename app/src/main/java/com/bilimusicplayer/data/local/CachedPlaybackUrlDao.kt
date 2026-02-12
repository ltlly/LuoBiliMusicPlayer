package com.bilimusicplayer.data.local

import androidx.room.*
import com.bilimusicplayer.data.model.CachedPlaybackUrl

/**
 * Data Access Object for cached playback URLs
 */
@Dao
interface CachedPlaybackUrlDao {

    @Query("SELECT * FROM cached_playback_urls WHERE bvid = :bvid")
    suspend fun getCachedUrl(bvid: String): CachedPlaybackUrl?

    @Query("SELECT * FROM cached_playback_urls WHERE bvid IN (:bvids)")
    suspend fun getCachedUrls(bvids: List<String>): List<CachedPlaybackUrl>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedUrl(cachedUrl: CachedPlaybackUrl)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedUrls(cachedUrls: List<CachedPlaybackUrl>)

    @Delete
    suspend fun deleteCachedUrl(cachedUrl: CachedPlaybackUrl)

    @Query("DELETE FROM cached_playback_urls WHERE bvid = :bvid")
    suspend fun deleteCachedUrlByBvid(bvid: String)

    @Query("DELETE FROM cached_playback_urls")
    suspend fun deleteAllCachedUrls()

    @Query("DELETE FROM cached_playback_urls WHERE expiresAt < :currentTime")
    suspend fun deleteExpiredUrls(currentTime: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM cached_playback_urls")
    suspend fun getCachedUrlCount(): Int

    /**
     * Check if URL is cached and not expired
     */
    suspend fun isCachedAndValid(bvid: String): Boolean {
        val cached = getCachedUrl(bvid) ?: return false
        return cached.expiresAt > System.currentTimeMillis()
    }
}
