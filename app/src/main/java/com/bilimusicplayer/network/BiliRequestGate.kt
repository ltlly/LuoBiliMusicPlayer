package com.bilimusicplayer.network

import android.content.Context
import com.bilimusicplayer.service.download.DownloadSettingsManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class BiliRateLimitedException(val httpCode: Int? = null, val apiCode: Int? = null) : IOException(
    "B站暂时限制请求" + (httpCode?.let { "（HTTP $it，接口 ${apiCode ?: "无"}）" } ?: "") + "，冷却后自动重试"
)

/** One in-flight API request; optional spacing, with persistent cooldown on actual blocking. */
class BiliRequestGate(context: Context) : Interceptor {
    private val prefs = context.getSharedPreferences("bili_request_gate", Context.MODE_PRIVATE)
    private val settings = DownloadSettingsManager(context)
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.host != "api.bilibili.com") return chain.proceed(chain.request())
        return lock.withLock {
            if (prefs.getLong("cooldown", 0) > System.currentTimeMillis()) throw BiliRateLimitedException()
            val interval = runBlocking { settings.getApiRequestIntervalMs() }
            val delay = if (interval > 0) prefs.getLong("last", 0) + interval - System.currentTimeMillis() else 0L
            if (delay > 0) Thread.sleep(delay.coerceAtMost(60_000))
            if (chain.call().isCanceled()) throw IOException("请求已取消")
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
            val response = chain.proceed(chain.request())
            val code = runCatching {
                com.google.gson.JsonParser.parseString(response.peekBody(65536).string())
                    .asJsonObject.get("code")?.asInt
            }.getOrNull()
            if (response.code in listOf(412, 429) || code in listOf(-352, -509)) {
                val level = (prefs.getInt("level", 0) + 1).coerceAtMost(4)
                prefs.edit().putInt("level", level)
                    .putLong("cooldown", System.currentTimeMillis() + 300_000L * level).commit()
                android.util.Log.w("BiliRequestGate", "收到风控响应：HTTP ${response.code}，接口 ${code ?: "无"}，冷却 ${level * 5} 分钟")
                response.close()
                throw BiliRateLimitedException(response.code, code)
            }
            if (response.isSuccessful && code == 0) prefs.edit().putInt("level", 0).apply()
            response
        }
    }
    companion object { private val lock = ReentrantLock(true) }
}
