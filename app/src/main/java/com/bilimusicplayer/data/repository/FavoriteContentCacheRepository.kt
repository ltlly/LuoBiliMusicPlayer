package com.bilimusicplayer.data.repository

import android.util.Log
import androidx.room.withTransaction
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
        val entities = mediaList.mapIndexed { index, media ->
            media.toCachedEntity(folderId, index)
        }
        database.withTransaction {
            dao.deleteByFolderId(folderId)
            dao.insertAll(entities)
        }
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
     * 增量同步收藏夹：先用 x/v3/fav/resource/ids 一次拿全量bvid（1次请求），
     * 只抓取"包含未知条目"的页——新收藏集中在开头时通常只需1-2页，
     * 而不是把几十页全部重拉。返回新抓取的条目数。
     */
    suspend fun syncFolderIncremental(
        repository: com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository,
        folderId: Long,
        delayMs: Long = 500L
    ): Int {
        val ids = repository.getFavoriteResourceIds(folderId)
        if (ids.isEmpty()) return 0
        val known = dao.getBvidsByFolderId(folderId).toHashSet()

        // 找缺失条目所在的页码（收藏夹列表按收藏时间倒序，ids顺序与之对应）
        val pagesNeeded = ids.mapIndexedNotNull { index, r ->
            val bv = r.bvid
            if (bv == null || bv !in known) index / 20 + 1 else null
        }.toSortedSet()
        if (pagesNeeded.isEmpty()) return 0

        var fetched = 0
        for (page in pagesNeeded) {
            val response = repository.getFavoriteResources(folderId, page, 20)
            if (response.isSuccessful && response.body()?.code == 0) {
                val medias = response.body()?.data?.medias ?: emptyList()
                if (medias.isEmpty()) break
                appendToCache(folderId, medias, (page - 1) * 20)
                fetched += medias.size
            } else {
                Log.w(TAG, "增量同步第${page}页失败: ${response.code()}")
                break
            }
            kotlinx.coroutines.delay(delayMs)
        }
        Log.d(TAG, "增量同步完成 folderId=$folderId: ${pagesNeeded.size}页/新增约${fetched}条 (ids共${ids.size})")
        return fetched
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
        upperMid = this.upper?.mid ?: 0L,
        upperName = this.upper?.name ?: "",
        upperFace = this.upper?.face ?: "",
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

