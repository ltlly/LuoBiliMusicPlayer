package com.bilimusicplayer.network.bilibili.auth

import java.security.MessageDigest

/**
 * Bilibili API signature utilities
 * Reference: https://github.com/SocialSisterYi/bilibili-API-collect
 */
object BiliSignature {
    // BiliTV AppKey and AppSecret
    const val APP_KEY = "4409e2ce8ffd12b8"
    private const val APP_SECRET = "59b43e04ad6965f34319062b478f83dd"

    /**
     * Signs the request body parameters with MD5
     * Formula: MD5(sortedParams + AppSecret)
     */
    fun signBody(params: Map<String, String>): String {
        val sortedParams = params.toSortedMap()
        val queryString = sortedParams.entries.joinToString("&") { "${it.key}=${it.value}" }
        val signString = queryString + APP_SECRET
        return md5(signString)
    }

    /**
     * Parses body parameters to query string format
     */
    fun parseBodyParams(params: Map<String, String>): String {
        return params.entries.joinToString("&") { "${it.key}=${it.value}" }
    }

    /**
     * Computes MD5 hash
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
