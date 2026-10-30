package com.bilimusicplayer.data.repository

import com.bilimusicplayer.data.model.CachedFavoriteMedia
import com.bilimusicplayer.network.bilibili.favorite.FavoriteMedia
import com.bilimusicplayer.network.bilibili.favorite.FavResourceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FillMissingChunksTest {

    private fun r(id: Long, bv: String?) = FavResourceId(id = id, type = 2, bvid = bv)

    private fun media(id: Long, bv: String) = FavoriteMedia(
        id = id, type = 2, title = "t$id", cover = "c", bvid = bv,
        upper = null, duration = 1, intro = "", ctime = 0, pubtime = 0,
    )

    @Test
    fun `块返回19比20时继续下一块且缺失id写失效占位`() = runBlocking {
        val ids = (1L..40L).map { r(it, "BV$it") }
        val ordered = ids
        val written = mutableListOf<CachedFavoriteMedia>()
        val batches = mutableListOf<List<FavoriteMedia>>()

        // 第一块缺 id=20（失效），第二块全返回
        val filled = fillMissingChunks(
            folderId = 1L,
            ids = ids,
            ordered = ordered,
            fetchChunk = { chunk ->
                chunk.filter { it.id != 20L }.map { media(it.id, it.bvid!!) }
            },
            writeEntities = { written += it },
            onBatch = { batches += it },
        )

        // 两块都处理了：40 条全部"补到"（39 真 + 1 失效占位）
        assertEquals(2, batches.size)
        assertEquals(40, filled.size)
        assertEquals(40, written.size)
        val dead = written.single { it.id == 20L }
        assertEquals(DEAD_MEDIA_TITLE, dead.title)
        assertEquals("BV20", dead.bvid)
        // position 取 ids 下标（id=20 是第 19 位）
        assertEquals(19, dead.position)
        assertTrue(batches[0].any { it.title == DEAD_MEDIA_TITLE })
    }

    @Test
    fun `fetchChunk返回null时停止且不写`() = runBlocking {
        val ids = (1L..40L).map { r(it, "BV$it") }
        val written = mutableListOf<CachedFavoriteMedia>()
        var calls = 0

        val filled = fillMissingChunks(
            folderId = 1L,
            ids = ids,
            ordered = ids,
            fetchChunk = { chunk ->
                calls++
                if (calls == 2) null else chunk.map { media(it.id, it.bvid!!) }
            },
            writeEntities = { written += it },
            onBatch = {},
        )

        assertEquals(2, calls)
        assertEquals(20, filled.size)
        assertEquals(20, written.size)
    }

    @Test
    fun `失效id进入已知集合后不再出现在待请求列表`() {
        // 失效条目已按 bvid 写入缓存 → known 包含 BV20 → 不再请求
        val ids = listOf(r(1, "BV1"), r(20, "BV20"), r(30, "BV30"))
        val planned = planMissingIds(
            knownBvids = setOf("BV20"),
            ids = ids,
            priorityBvids = emptyList(),
        )
        assertEquals(listOf("BV1", "BV30"), planned.map { it.bvid })
    }
}
