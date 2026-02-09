package com.bilimusicplayer.network.bilibili.favorite

import android.util.Log
import retrofit2.Response

/**
 * Repository for Bilibili favorite operations
 */
class BiliFavoriteRepository(
    private val api: BiliFavoriteApi
) {

    /**
     * Initialize WBI keys from navigation API
     */
    suspend fun initWbiKeys(): Boolean {
        return try {
            val response = api.getNavInfo()
            if (response.isSuccessful && response.body()?.code == 0) {
                val wbiImg = response.body()?.data?.wbiImg
                if (wbiImg != null) {
                    val keys = WbiSignature.extractKeys(wbiImg.imgUrl, wbiImg.subUrl)
                    WbiSignature.updateKeys(keys.imgKey, keys.subKey)
                    Log.d(TAG, "WBI keys initialized successfully")
                    true
                } else {
                    Log.e(TAG, "WBI image data not found in nav response")
                    false
                }
            } else {
                Log.e(TAG, "Failed to get nav info: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WBI keys", e)
            false
        }
    }

    /**
     * Get user's favorite folders
     */
    suspend fun getFavoriteFolders(userId: Long): Response<FavoriteListResponse> {
        return api.getFavoriteFolders(userId)
    }

    /**
     * Get favorite folder contents with WBI signature
     * @param mediaId Favorite folder ID
     * @param pageNumber Page number (default 1)
     * @param pageSize Page size (default 20)
     * @param keyword Search keyword (optional)
     */
    suspend fun getFavoriteResources(
        mediaId: Long,
        pageNumber: Int = 1,
        pageSize: Int = 20,
        keyword: String? = null
    ): Response<FavoriteResourceResponse> {
        // Ensure WBI keys are initialized
        if (!WbiSignature.isInitialized()) {
            initWbiKeys()
        }

        val params = mutableMapOf(
            "media_id" to mediaId.toString(),
            "pn" to pageNumber.toString(),
            "ps" to pageSize.toString(),
            "platform" to "web"
        )

        // Add keyword if provided
        if (!keyword.isNullOrBlank()) {
            params["keyword"] = keyword
            Log.d(TAG, "添加搜索关键词: $keyword")
        }

        Log.d(TAG, "请求参数: $params")

        // Sign the parameters with WBI (without URL encoding)
        val signedParams = WbiSignature.signParams(params)

        Log.d(TAG, "签名后参数: $signedParams")

        return api.getFavoriteResources(signedParams)
    }

    /**
     * Get video detail by BVID
     */
    suspend fun getVideoDetail(bvid: String): Response<VideoDetailResponse> {
        return api.getVideoDetail(bvid)
    }

    /**
     * Get play URL with WBI signature
     * @param cid Video CID
     * @param bvid Video BVID
     * @param quality Video quality (default 127 to request highest available quality)
     */
    suspend fun getPlayUrl(
        cid: Long,
        bvid: String,
        quality: Int = 127  // Request highest quality, actual quality depends on video source and account level
    ): Response<PlayUrlResponse> {
        // Ensure WBI keys are initialized
        if (!WbiSignature.isInitialized()) {
            initWbiKeys()
        }

        val params = mapOf(
            "cid" to cid.toString(),
            "bvid" to bvid,
            "qn" to quality.toString(),
            "fnval" to "16",  // Get DASH format
            "fnver" to "0",
            "fourk" to "1"
        )

        // Sign the parameters with WBI (without URL encoding)
        val signedParams = WbiSignature.signParams(params)

        return api.getPlayUrl(signedParams)
    }

    /**
     * Get all videos from a favorite folder (handles pagination)
     */
    suspend fun getAllFavoriteVideos(mediaId: Long): List<FavoriteMedia> {
        val allVideos = mutableListOf<FavoriteMedia>()
        var currentPage = 1
        var totalCount = 0
        val pageSize = 20

        try {
            do {
                val response = getFavoriteResources(mediaId, currentPage, pageSize)
                if (response.isSuccessful && response.body()?.code == 0) {
                    val data = response.body()?.data
                    if (data != null) {
                        totalCount = data.info.mediaCount
                        data.medias?.let { allVideos.addAll(it) }
                        currentPage++
                    } else {
                        break
                    }
                } else {
                    Log.e(TAG, "Failed to get favorite resources: ${response.code()}")
                    break
                }
            } while (allVideos.size < totalCount)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all favorite videos", e)
        }

        return allVideos
    }

    /**
     * Select the best quality audio stream from available audio streams
     * Prioritizes by audio quality ID (higher is better)
     * @param audioStreams List of available audio streams
     * @return Best quality audio stream or null if list is empty
     */
    fun selectBestAudioStream(audioStreams: List<DashStream>?): DashStream? {
        if (audioStreams.isNullOrEmpty()) {
            Log.w(TAG, "No audio streams available")
            return null
        }

        // Sort by audio quality ID (higher ID = better quality)
        // Audio quality IDs: 30216 (64K), 30232 (132K), 30280 (192K Hi-Res), etc.
        val bestStream = audioStreams.maxByOrNull { it.id }

        if (bestStream != null) {
            Log.d(TAG, "Selected audio quality: ID=${bestStream.id}, bandwidth=${bestStream.bandwidth}, codecs=${bestStream.codecs}")
            Log.d(TAG, "Available audio streams: ${audioStreams.map { "ID=${it.id}(${it.bandwidth}bps)" }.joinToString(", ")}")
        }

        return bestStream
    }

    companion object {
        private const val TAG = "BiliFavoriteRepository"
    }
}
