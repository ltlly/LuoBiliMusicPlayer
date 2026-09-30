package com.bilimusicplayer.network.bilibili.favorite

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class CachedCidRecoveryTest {
    private val requests = mutableListOf<String>()
    private fun repository(stale: Boolean, freshCid: Long = 202L): BiliFavoriteRepository {
        val api = Proxy.newProxyInstance(BiliFavoriteApi::class.java.classLoader,
            arrayOf(BiliFavoriteApi::class.java)) { _, method, args ->
            when (method.name) {
                "getPlayUrl" -> {
                    val cid = (args!![0] as Map<*, *>)["cid"].toString()
                    requests += "play:$cid"
                    Response.success(PlayUrlResponse(if (stale && cid == "101") -404 else 0, "", null))
                }
                "getVideoDetail" -> {
                    requests += "detail"
                    Response.success(Gson().fromJson("""{"code":0,"message":"OK","data":{"cid":$freshCid}}""", VideoDetailResponse::class.java))
                }
                else -> error("Unexpected API call: ${method.name}")
            }
        } as BiliFavoriteApi
        return BiliFavoriteRepository(api)
    }
    @Before fun initializeKeys() { WbiSignature.updateKeys("a".repeat(32), "b".repeat(32)) }
    @After fun clearKeys() { WbiSignature.clearKeys() }

    @Test fun validCachedCidNeedsOnlyOnePlaybackRequest() = runBlocking {
        val result = repository(false).getPlayUrlWithFreshCidFallback(101, "BV_test", true)
        assertEquals(101L, result.cid)
        assertEquals(listOf("play:101"), requests)
    }
    @Test fun rejectedOldCidRefreshesOnceAndReturnsNewCidForPersistence() = runBlocking {
        val result = repository(true).getPlayUrlWithFreshCidFallback(101, "BV_test", true)
        assertEquals(202L, result.cid)
        assertEquals(0, result.response.body()?.code)
        assertEquals(listOf("play:101", "detail", "play:202"), requests)
    }
    @Test fun unchangedCidDoesNotRepeatTheRejectedPlaybackRequest() = runBlocking {
        val result = repository(true, 101).getPlayUrlWithFreshCidFallback(101, "BV_test", true)
        assertEquals(-404, result.response.body()?.code)
        assertEquals(listOf("play:101", "detail"), requests)
    }
    @Test fun freshlyFetchedCidDoesNotCauseAnotherVideoDetailRequest() = runBlocking {
        val result = repository(true).getPlayUrlWithFreshCidFallback(101, "BV_test", false)
        assertEquals(-404, result.response.body()?.code)
        assertEquals(listOf("play:101"), requests)
    }
}
