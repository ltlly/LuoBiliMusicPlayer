package com.bilimusicplayer.data.repository

import com.bilimusicplayer.network.bilibili.favorite.FavResourceId
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanMissingIdsTest {

    private fun r(id: Long, bv: String?) = FavResourceId(id = id, type = 2, bvid = bv)

    @Test
    fun `跳过已缓存条目`() {
        val ids = listOf(r(1, "BV1"), r(2, "BV2"), r(3, "BV3"))
        val planned = planMissingIds(knownBvids = setOf("BV2"), ids = ids, priorityBvids = emptyList())
        assertEquals(listOf("BV1", "BV3"), planned.map { it.bvid })
    }

    @Test
    fun `priority条目排最前且保持priority顺序`() {
        val ids = listOf(r(1, "BV1"), r(2, "BV2"), r(3, "BV3"), r(4, "BV4"))
        val planned = planMissingIds(
            knownBvids = emptySet(),
            ids = ids,
            priorityBvids = listOf("BV4", "BV2"),
        )
        assertEquals(listOf("BV4", "BV2", "BV1", "BV3"), planned.map { it.bvid })
    }

    @Test
    fun `空bv_id条目被忽略`() {
        val ids = listOf(r(1, null), r(2, "BV2"))
        val planned = planMissingIds(knownBvids = emptySet(), ids = ids, priorityBvids = emptyList())
        assertEquals(listOf("BV2"), planned.map { it.bvid })
    }
}
