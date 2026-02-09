package com.bilimusicplayer.network.bilibili.favorite

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Bilibili WBI Signature Implementation
 * Reference: https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/misc/sign/wbi.md
 */
object WbiSignature {
    // Mixin key encoding table
    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52
    )

    /**
     * WBI Keys holder
     */
    data class WbiKeys(
        val imgKey: String = "",
        val subKey: String = ""
    )

    private var wbiKeys = WbiKeys()

    /**
     * Update WBI keys
     */
    fun updateKeys(imgKey: String, subKey: String) {
        wbiKeys = WbiKeys(imgKey, subKey)
    }

    /**
     * Get mixin key from img_key and sub_key
     */
    private fun getMixinKey(orig: String): String {
        val sb = StringBuilder()
        for (index in mixinKeyEncTab) {
            if (index < orig.length) {
                sb.append(orig[index])
            }
        }
        return sb.toString().take(32)
    }

    /**
     * Encode WBI signature for query parameters
     * @param params Query parameters map
     * @return Signed query string with w_rid
     */
    fun encWbi(params: Map<String, String>): String {
        if (wbiKeys.imgKey.isEmpty() || wbiKeys.subKey.isEmpty()) {
            throw IllegalStateException("WBI keys not initialized. Call updateKeys() first.")
        }

        val mixinKey = getMixinKey(wbiKeys.imgKey + wbiKeys.subKey)
        val wts = System.currentTimeMillis() / 1000
        val chrFilter = Regex("[!'()*]")

        // Add wts timestamp
        val newParams = params.toMutableMap()
        newParams["wts"] = wts.toString()

        // Sort parameters by key and build query string
        val query = newParams.toSortedMap().entries.joinToString("&") { (key, value) ->
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val filteredValue = value.replace(chrFilter, "")
            val encodedValue = URLEncoder.encode(filteredValue, "UTF-8")
            "$encodedKey=$encodedValue"
        }

        // Calculate w_rid signature
        val wbiSign = md5(query + mixinKey)

        return "$query&w_rid=$wbiSign"
    }

    /**
     * Generate WBI signature and return parameters map (without URL encoding)
     * This is used with Retrofit which will handle URL encoding
     * @param params Query parameters map
     * @return Signed parameters map with wts and w_rid
     */
    fun signParams(params: Map<String, String>): Map<String, String> {
        if (wbiKeys.imgKey.isEmpty() || wbiKeys.subKey.isEmpty()) {
            throw IllegalStateException("WBI keys not initialized. Call updateKeys() first.")
        }

        val mixinKey = getMixinKey(wbiKeys.imgKey + wbiKeys.subKey)
        val wts = System.currentTimeMillis() / 1000
        val chrFilter = Regex("[!'()*]")

        // Add wts timestamp and filter special characters
        val newParams = params.mapValues { (_, value) ->
            value.replace(chrFilter, "")
        }.toMutableMap()
        newParams["wts"] = wts.toString()

        // Sort parameters by key and build query string for signing
        val query = newParams.toSortedMap().entries.joinToString("&") { (key, value) ->
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val encodedValue = URLEncoder.encode(value, "UTF-8")
            "$encodedKey=$encodedValue"
        }

        // Calculate w_rid signature
        val wbiSign = md5(query + mixinKey)
        newParams["w_rid"] = wbiSign

        return newParams
    }

    /**
     * Extract img_key and sub_key from WBI image URLs
     */
    fun extractKeys(imgUrl: String, subUrl: String): WbiKeys {
        val imgKey = imgUrl.substringAfterLast('/').substringBeforeLast('.')
        val subKey = subUrl.substringAfterLast('/').substringBeforeLast('.')
        return WbiKeys(imgKey, subKey)
    }

    /**
     * Calculate MD5 hash
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Check if WBI keys are initialized
     */
    fun isInitialized(): Boolean {
        return wbiKeys.imgKey.isNotEmpty() && wbiKeys.subKey.isNotEmpty()
    }

    /**
     * Clear WBI keys
     */
    fun clearKeys() {
        wbiKeys = WbiKeys()
    }
}
