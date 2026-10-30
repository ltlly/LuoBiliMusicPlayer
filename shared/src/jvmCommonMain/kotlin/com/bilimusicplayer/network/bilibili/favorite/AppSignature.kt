package com.bilimusicplayer.network.bilibili.favorite

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * APP 端签名（appkey + sign，MD5(query+appsec)）。
 * 官方安卓"粉版"公开密钥，bilimiao/PiliPala 等三方客户端同款。
 * 与 WBI 签名是不同风控通道，且无需先调 nav 接口初始化密钥——省一次请求。
 */
object AppSignature {
    const val APPKEY = "1d8b6e7d45233436"
    private const val APPSEC = "560c52ccd288fed045859ed18bffd973"

    /** 为参数注入 appkey/mobi_app/platform/ts 并计算 sign。 */
    fun signParams(params: Map<String, String>): Map<String, String> {
        val full = params.toMutableMap().apply {
            put("appkey", APPKEY)
            putIfAbsent("mobi_app", "android")
            putIfAbsent("platform", "android")
            putIfAbsent("ts", (System.currentTimeMillis() / 1000).toString())
        }
        val query = full.toSortedMap().entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val md5 = MessageDigest.getInstance("MD5")
            .digest((query + APPSEC).toByteArray())
            .joinToString("") { "%02x".format(it) }
        full["sign"] = md5
        return full
    }
}
