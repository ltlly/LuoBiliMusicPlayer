package com.bilimusicplayer.network.bilibili.favorite

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import retrofit2.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FavResourceInfosTest {

    @Test
    fun `FavResourceInfosResponse 解析 bv_id 字段`() {
        val json = """
            {"code":0,"message":"0","data":[{"id":1,"type":2,"title":"歌A","cover":"","bv_id":"BV1a","upper":{"mid":9,"name":"up"},"duration":100,"intro":"","ctime":0,"pubtime":0}]}
        """.trimIndent()
        val resp = Gson().fromJson(json, FavResourceInfosResponse::class.java)
        assertEquals(0, resp.code)
        assertEquals("BV1a", resp.data?.first()?.bvid)
        assertEquals("up", resp.data?.first()?.upper?.name)
    }

    @Test
    fun `FavResourceInfosResponse 解析 bvid 字段`() {
        val json = """
            {"code":0,"message":"0","data":[{"id":2,"type":2,"title":"歌B","cover":"","bvid":"BV1b","upper":null,"duration":0,"intro":"","ctime":0,"pubtime":0}]}
        """.trimIndent()
        val resp = Gson().fromJson(json, FavResourceInfosResponse::class.java)
        assertEquals("BV1b", resp.data?.first()?.bvid)
    }

    private class FakeApi : BiliFavoriteApi {
        val resourceParams = mutableListOf<String>()

        override suspend fun getFavoriteResourceInfos(
            params: Map<String, String>
        ): Response<FavResourceInfosResponse> {
            val resources = params["resources"].orEmpty()
            resourceParams += resources
            val data = resources.split(",").filter { it.isNotBlank() }.map { part ->
                val (id, type) = part.split(":")
                FavoriteMedia(
                    id = id.toLong(), type = type.toInt(), title = "t$id",
                    cover = "", bvid = "BV$id", upper = null, duration = 1,
                    intro = "", ctime = 0, pubtime = 0,
                )
            }
            return Response.success(FavResourceInfosResponse(0, "", data))
        }

        override suspend fun getNavInfo() = throw NotImplementedError()
        override suspend fun getFavoriteFolders(userId: Long) = throw NotImplementedError()
        override suspend fun getFavoriteResources(params: Map<String, String>) = throw NotImplementedError()
        override suspend fun getVideoDetail(params: Map<String, String>) = throw NotImplementedError()
        override suspend fun getFavoriteResourceIds(params: Map<String, String>) = throw NotImplementedError()
        override suspend fun getPlayUrl(params: Map<String, String>) = throw NotImplementedError()
        override suspend fun getPlayUrlWbi(params: Map<String, String>) = throw NotImplementedError()
    }

    @Test
    fun `getFavoriteResourceInfos 每20条分块`() = runBlocking {
        val api = FakeApi()
        val repo = BiliFavoriteRepository(api)
        val ids = (1L..45L).map { FavResourceId(id = it, type = 2, bvid = "BV$it") }

        val result = repo.getFavoriteResourceInfos(ids)

        assertEquals(3, api.resourceParams.size)
        assertEquals(20, api.resourceParams[0].split(",").size)
        assertEquals(20, api.resourceParams[1].split(",").size)
        assertEquals(5, api.resourceParams[2].split(",").size)
        assertTrue(api.resourceParams[0].startsWith("1:2,"))
        assertEquals(45, result.size)
    }
}
