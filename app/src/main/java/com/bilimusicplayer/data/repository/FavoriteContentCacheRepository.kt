package com.bilimusicplayer.data.repository

import android.util.Log
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.CachedFavoriteMedia
import com.bilimusicplayer.network.bilibili.favorite.FavoriteMedia
import com.bilimusicplayer.network.bilibili.favorite.Upper

/**
 * Repository for caching favorite folder content (media list)
 * Strategy:
 * 1. Return cached data immediately if available (all cached pages)
 * 2. Refresh page 1 from API in background without wiping later pages
 * 3. Append new pages to cache as user scrolls
 */
class FavoriteContentCacheRepository(private val database: AppDatabase) {

    private val dao = database.cachedFavoriteMediaDao()

    /**
     * Get cached media list for a folder (returns ALL cached pages)
     */
    suspend fun getCachedMediaList(folderId: Long): List<FavoriteMedia>? {
        val cached = dao.getByFolderId(folderId)
        if (cached.isEmpty()) return null

        Log.d(TAG, "从缓存加载 ${cached.size} 条收藏夹内容, folderId=$folderId")
        return cached.map { it.toFavoriteMedia() }
    }

    /**
     * Save media list to cache for the first time (replaces everything)
     */
    suspend fun cacheMediaList(folderId: Long, mediaList: List<FavoriteMedia>) {
        dao.deleteByFolderId(folderId)
        val entities = mediaList.mapIndexed { index, media ->
            media.toCachedEntity(folderId, index)
        }
        dao.insertAll(entities)
        Log.d(TAG, "缓存 ${entities.size} 条收藏夹内容, folderId=$folderId")
    }

    /**
     * Refresh a specific page in the cache without wiping other pages.
     * Uses REPLACE strategy so existing items at same (folderId, bvid) are updated.
     */
    suspend fun refreshPage(folderId: Long, mediaList: List<FavoriteMedia>, pageNumber: Int, pageSize: Int = 20) {
        val startPosition = (pageNumber - 1) * pageSize
        val entities = mediaList.mapIndexed { index, media ->
            media.toCachedEntity(folderId, startPosition + index)
        }
        // REPLACE on conflict by (folderId, bvid) — updates existing, inserts new
        dao.insertAll(entities)
        Log.d(TAG, "刷新缓存第${pageNumber}页 ${entities.size} 条, folderId=$folderId")
    }

    /**
     * Append more media to cache (for pagination)
     */
    suspend fun appendToCache(folderId: Long, mediaList: List<FavoriteMedia>, startPosition: Int) {
        val entities = mediaList.mapIndexed { index, media ->
            media.toCachedEntity(folderId, startPosition + index)
        }
        dao.insertAll(entities)
        Log.d(TAG, "追加缓存 ${entities.size} 条, 起始位置=$startPosition, folderId=$folderId")
    }

    /**
     * Get number of cached items for a folder
     */
    suspend fun getCachedCount(folderId: Long): Int {
        return dao.getCountByFolderId(folderId)
    }

    /**
     * Clear cache for a folder
     */
    suspend fun clearCache(folderId: Long) {
        dao.deleteByFolderId(folderId)
    }

    companion object {
        private const val TAG = "FavoriteContentCache"
    }
}

/**
 * Convert FavoriteMedia to cached entity
 */
private fun FavoriteMedia.toCachedEntity(folderId: Long, position: Int): CachedFavoriteMedia {
    return CachedFavoriteMedia(
        folderId = folderId,
        id = this.id,
        type = this.type,
        title = this.title,
        cover = this.cover,
        bvid = this.bvid,
        upperMid = this.upper.mid,
        upperName = this.upper.name,
        upperFace = this.upper.face,
        duration = this.duration,
        intro = this.intro,
        ctime = this.ctime,
        pubtime = this.pubtime,
        position = position
    )
}

/**
 * Convert cached entity back to FavoriteMedia
 */
private fun CachedFavoriteMedia.toFavoriteMedia(): FavoriteMedia {
    return FavoriteMedia(
        id = this.id,
        type = this.type,
        title = this.title,
        cover = this.cover,
        bvid = this.bvid,
        upper = Upper(
            mid = this.upperMid,
            name = this.upperName,
            face = this.upperFace
        ),
        duration = this.duration,
        intro = this.intro,
        ctime = this.ctime,
        pubtime = this.pubtime
    )
}

