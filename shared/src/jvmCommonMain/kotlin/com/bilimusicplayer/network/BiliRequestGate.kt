package com.bilimusicplayer.network

import com.bilimusicplayer.core.Log
import com.bilimusicplayer.settings.AppStorage
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BiliRateLimitedException(val httpCode: Int? = null, val apiCode: Int? = null) : IOException(
    "B站暂时限制请求" + (httpCode?.let { "（HTTP $it，接口 ${apiCode ?: "无"}）" } ?: "") + "，冷却后自动重试"
)

/**
 * Serializes API calls and applies the optional request spacing plus a persistent
 * cooldown when Bilibili actually returns a rate-limit response.
 */
class BiliRequestGate(private val storage: AppStorage) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.host != "api.bilibili.com") return chain.proceed(chain.request())

        return lock.withLock {
            val now = System.currentTimeMillis()
            if (storage.gateCooldownUntil > now) throw BiliRateLimitedException()

            val interval = storage.downloadSettings.value.apiRequestIntervalMs
            val delay = if (interval > 0) storage.gateLastRequestAt + interval - now else 0L
            if (delay > 0) Thread.sleep(delay.coerceAtMost(60_000))

            if (chain.call().isCanceled()) throw IOException("请求已取消")
            storage.gateLastRequestAt = System.currentTimeMillis()

            val response = chain.proceed(chain.request())
            val code = runCatching {
                com.google.gson.JsonParser.parseString(response.peekBody(65536).string())
                    .asJsonObject.get("code")?.asInt
            }.getOrNull()

            if (response.code in listOf(412, 429) || code in listOf(-352, -509)) {
                val level = (storage.gateLevel + 1).coerceAtMost(4)
                storage.gateLevel = level
                storage.gateCooldownUntil = System.currentTimeMillis() + 300_000L * level
                Log.w(
                    "BiliRequestGate",
                    "收到风控响应：HTTP ${response.code}，接口 ${code ?: "无"}，冷却 ${level * 5} 分钟",
                )
                response.close()
                throw BiliRateLimitedException(response.code, code)
            }

            if (response.isSuccessful && code == 0) storage.gateLevel = 0
            response
        }
    }

    private companion object {
        val lock = ReentrantLock(true)
    }
}
