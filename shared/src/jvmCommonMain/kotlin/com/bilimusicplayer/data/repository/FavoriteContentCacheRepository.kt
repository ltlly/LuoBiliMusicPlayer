package com.bilimusicplayer.data.repository

import com.bilimusicplayer.core.Log
import androidx.room.Transactor
import androidx.room.useWriterConnection
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.CachedFavoriteMedia
import com.bilimusicplayer.network.bilibili.favorite.FavoriteMedia
import com.bilimusicplayer.network.bilibili.favorite.FavResourceId
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
        database.useWriterConnection { transaction ->
            transaction.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                dao.deleteByFolderId(folderId)
                dao.insertAll(entities)
            }
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
     * 再把缓存里缺失的条目用 x/v3/fav/resource/infos 批量补齐。
     * 返回新抓取的条目数。
     */
    suspend fun syncFolderIncremental(
        repository: com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository,
        folderId: Long,
        delayMs: Long = 500L
    ): Int {
        val ids = repository.getFavoriteResourceIds(folderId)
        if (ids.isEmpty()) return 0
        var fetched = 0
        fillMissing(repository, folderId, ids, delayMs = delayMs) { batch ->
            fetched += batch.size
        }
        Log.d(TAG, "增量同步完成 folderId=$folderId: 新增${fetched}条 (ids共${ids.size})")
        return fetched
    }

    /**
     * 用 resource/infos 批量补全缓存里没有的条目。
     * 补全顺序：priorityBvids 命中的先（保持 priorityBvids 顺序），其余按 ids 顺序。
     * 写缓存的 position 取条目在 ids 中的下标，与 resource/ids 的顺序一致。
     * infos 不返回的 id 视为失效视频，同样写入缓存占位（标题"已失效视频"），
     * 下次不再请求。每块（含失效占位）经 [onBatch] 回调，全部补到的一并返回。
     */
    suspend fun fillMissing(
        repository: com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository,
        folderId: Long,
        ids: List<FavResourceId>,
        priorityBvids: List<String> = emptyList(),
        delayMs: Long = 0L,
        onBatch: suspend (List<FavoriteMedia>) -> Unit = {},
    ): List<FavoriteMedia> {
        val known = dao.getBvidsByFolderId(folderId).toHashSet()
        val ordered = planMissingIds(known, ids, priorityBvids)
        if (ordered.isEmpty()) return emptyList()

        val filled = fillMissingChunks(
            folderId = folderId,
            ids = ids,
            ordered = ordered,
            delayMs = delayMs,
            fetchChunk = { repository.getFavoriteResourceInfosChunk(it) },
            writeEntities = { dao.insertAll(it) },
            onBatch = onBatch,
        )
        if (filled.isNotEmpty()) {
            Log.d(TAG, "补全收藏夹条目 ${filled.size}/${ordered.size} 条, folderId=$folderId")
        }
        return filled
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

/** infos 不返回的条目的占位标题（与 resource/list 对失效视频的表示一致） */
internal const val DEAD_MEDIA_TITLE = "已失效视频"

/**
 * 逐块补全循环，抽出便于测试：fetchChunk 返回 null = 接口失败 → 停止（下次再补）；
 * 返回的条目缺失于 chunk 的 id 视为失效视频，以占位条目一并写入、回调、返回。
 * position 始终取条目在原始 ids 中的下标。
 */
internal suspend fun fillMissingChunks(
    folderId: Long,
    ids: List<FavResourceId>,
    ordered: List<FavResourceId>,
    delayMs: Long = 0L,
    fetchChunk: suspend (List<FavResourceId>) -> List<FavoriteMedia>?,
    writeEntities: suspend (List<CachedFavoriteMedia>) -> Unit,
    onBatch: suspend (List<FavoriteMedia>) -> Unit,
): List<FavoriteMedia> {
    val positionById = ids.mapIndexed { index, r -> r.id to index }.toMap()
    val positionByBvid = ids.mapIndexedNotNull { index, r -> r.bvid?.let { it to index } }.toMap()

    val filled = mutableListOf<FavoriteMedia>()
    var first = true
    for (chunk in ordered.chunked(20)) {
        if (!first && delayMs > 0) kotlinx.coroutines.delay(delayMs)
        first = false
        val batch = fetchChunk(chunk) ?: break
        val returnedIds = batch.mapTo(HashSet()) { it.id }
        // infos 不返回的条目 = 已失效视频：写占位让"已知"集合覆盖它们，避免反复请求
        val dead = chunk.mapNotNull { r ->
            if (r.id in returnedIds || r.bvid == null) return@mapNotNull null
            FavoriteMedia(
                id = r.id, type = r.type, title = DEAD_MEDIA_TITLE, cover = "",
                bvid = r.bvid, upper = null, duration = 0,
                intro = "", ctime = 0, pubtime = 0,
            )
        }
        val all = batch + dead
        writeEntities(all.map { media ->
            val pos = positionById[media.id] ?: positionByBvid[media.bvid] ?: Int.MAX_VALUE
            media.toCachedEntity(folderId, pos)
        })
        filled += all
        onBatch(all)
    }
    return filled
}

/**
 * 算出需要补全的条目（缓存里没有的 id）及抓取顺序：
 * priorityBvids 命中的先（保持 priorityBvids 顺序），其余保持 ids 顺序。
 * 抽出为纯函数方便测试。
 */
internal fun planMissingIds(
    knownBvids: Set<String>,
    ids: List<FavResourceId>,
    priorityBvids: List<String>,
): List<FavResourceId> {
    val missing = ids.filter { it.bvid != null && it.bvid !in knownBvids }
    if (missing.isEmpty() || priorityBvids.isEmpty()) return missing
    val priorityRank = priorityBvids.withIndex().associate { (i, bv) -> bv to i }
    val (prio, rest) = missing.partition { it.bvid in priorityRank }
    return prio.sortedBy { priorityRank[it.bvid] } + rest
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