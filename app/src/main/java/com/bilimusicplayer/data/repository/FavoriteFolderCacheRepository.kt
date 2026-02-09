package com.bilimusicplayer.data.repository

import android.util.Log
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.BiliFavoriteFolder
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.network.bilibili.favorite.FavoriteFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Repository for managing favorite folder cache
 */
class FavoriteFolderCacheRepository(
    private val database: AppDatabase,
    private val biliRepository: BiliFavoriteRepository
) {

    private val folderDao = database.biliFavoriteFolderDao()

    /**
     * Get favorite folders with cache support
     * Strategy:
     * 1. Return cached data immediately if available
     * 2. Check if cache is stale (> 5 minutes)
     * 3. If stale, refresh in background
     */
    suspend fun getFavoriteFolders(
        userId: Long,
        forceRefresh: Boolean = false
    ): Result<List<BiliFavoriteFolder>> {
        return try {
            // Check cache first (unless force refresh)
            if (!forceRefresh) {
                val cachedFolders = folderDao.getAllFoldersOnce()
                if (cachedFolders.isNotEmpty()) {
                    Log.d(TAG, "从缓存加载 ${cachedFolders.size} 个收藏夹")

                    // Check if cache is stale
                    val isStale = folderDao.isCacheStale(maxAgeMs = 5 * 60 * 1000) // 5 minutes
                    if (!isStale) {
                        Log.d(TAG, "缓存新鲜，直接返回")
                        return Result.success(cachedFolders)
                    } else {
                        Log.d(TAG, "缓存过期，后台刷新")
                        // Return cached data first, refresh in background
                        CoroutineScope(Dispatchers.IO).launch {
                            refreshFoldersFromNetwork(userId)
                        }
                        return Result.success(cachedFolders)
                    }
                }
            }

            // No cache or force refresh, load from network
            Log.d(TAG, "从网络加载收藏夹列表")
            val folders = refreshFoldersFromNetwork(userId)
            Result.success(folders)
        } catch (e: Exception) {
            Log.e(TAG, "加载收藏夹失败", e)
            Result.failure(e)
        }
    }

    /**
     * Refresh folders from network and update cache
     */
    private suspend fun refreshFoldersFromNetwork(userId: Long): List<BiliFavoriteFolder> {
        // Initialize WBI keys if needed
        biliRepository.initWbiKeys()

        // Fetch from network
        val response = biliRepository.getFavoriteFolders(userId)
        if (response.isSuccessful && response.body()?.code == 0) {
            val networkFolders = response.body()?.data?.list ?: emptyList()
            Log.d(TAG, "从网络获取 ${networkFolders.size} 个收藏夹")

            // Convert to cache entities
            val cacheFolders = networkFolders.map { it.toCacheEntity() }

            // Update cache
            folderDao.deleteAllFolders()
            folderDao.insertFolders(cacheFolders)
            Log.d(TAG, "缓存已更新")

            return cacheFolders
        } else {
            throw Exception("加载失败: ${response.body()?.message ?: "未知错误"}")
        }
    }

    /**
     * Force refresh from network
     */
    suspend fun forceRefresh(userId: Long): Result<List<BiliFavoriteFolder>> {
        return try {
            val folders = refreshFoldersFromNetwork(userId)
            Result.success(folders)
        } catch (e: Exception) {
            Log.e(TAG, "强制刷新失败", e)
            Result.failure(e)
        }
    }

    /**
     * Clear all cached folders
     */
    suspend fun clearCache() {
        folderDao.deleteAllFolders()
        Log.d(TAG, "缓存已清空")
    }

    /**
     * Get folder count from cache
     */
    suspend fun getCachedFolderCount(): Int {
        return folderDao.getFolderCount()
    }

    /**
     * Check if cache exists
     */
    suspend fun hasCachedData(): Boolean {
        return folderDao.getFolderCount() > 0
    }

    companion object {
        private const val TAG = "FavoriteFolderCache"
    }
}

/**
 * Extension function to convert network model to cache entity
 */
private fun FavoriteFolder.toCacheEntity(): BiliFavoriteFolder {
    return BiliFavoriteFolder(
        id = this.id,
        fid = this.fid,
        mid = this.mid,
        title = this.title,
        cover = this.cover,
        mediaCount = this.mediaCount,
        attr = this.attr,
        ctime = this.ctime,
        mtime = this.mtime,
        cachedAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )
}

/**
 * Extension function to convert cache entity to network model
 */
fun BiliFavoriteFolder.toNetworkModel(): FavoriteFolder {
    return FavoriteFolder(
        id = this.id,
        fid = this.fid,
        mid = this.mid,
        title = this.title,
        cover = this.cover ?: "",  // Provide default empty string if null
        mediaCount = this.mediaCount,
        attr = this.attr,
        ctime = this.ctime,
        mtime = this.mtime
    )
}
