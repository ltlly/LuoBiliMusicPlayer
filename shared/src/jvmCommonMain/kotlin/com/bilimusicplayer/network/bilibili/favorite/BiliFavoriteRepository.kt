package com.bilimusicplayer.network.bilibili.favorite

import com.bilimusicplayer.core.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

/**
 * Repository for Bilibili favorite operations
 */
class BiliFavoriteRepository(
    private val api: BiliFavoriteApi
) {
    // Every API, including navigation and playback, passes RetrofitClient's global gate.
    private suspend fun <T> throttle(block: suspend () -> T): T = block()

    /**
     * Initialize WBI keys from navigation API
     */
    suspend fun initWbiKeys(): Boolean = wbiInitMutex.withLock {
        if (WbiSignature.isInitialized()) return@withLock true
        try {
            val response = throttle { api.getNavInfo() }
            val body = response.body()
            if (!response.isSuccessful || body?.code != 0) {
                throw WbiInitializationException("签名初始化失败（HTTP ${response.code()}，接口 ${body?.code ?: "无响应"}），本次未修改歌曲")
            }
            val wbiImg = body.data?.wbiImg
                ?: throw WbiInitializationException("签名服务未返回密钥，本次未修改歌曲")
            val keys = WbiSignature.extractKeys(wbiImg.imgUrl, wbiImg.subUrl)
            if (keys.imgKey.isBlank() || keys.subKey.isBlank()) {
                throw WbiInitializationException("签名服务返回了无效密钥，本次未修改歌曲")
            }
            WbiSignature.updateKeys(keys.imgKey, keys.subKey)
            Log.d(TAG, "WBI keys initialized successfully")
            true
        } catch (e: com.bilimusicplayer.network.BiliRateLimitedException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: WbiInitializationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WBI keys", e)
            val reason = when (e) {
                is java.net.UnknownHostException -> "无法解析签名服务器地址，请检查网络"
                is java.net.SocketTimeoutException -> "签名服务器响应超时，请稍后重试"
                is java.net.ConnectException -> "无法连接签名服务器，请检查网络"
                is java.io.IOException -> "签名请求未完成，请稍后重试"
                else -> "签名接口解析失败，请更新应用后重试"
            }
            throw WbiInitializationException(reason, e)
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
            if (!initWbiKeys()) {
                throw IllegalStateException("无法初始化WBI签名，请检查网络连接")
            }
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

        return throttle { api.getFavoriteResources(signedParams) }
    }

    /**
     * Get video detail by BVID (with WBI signature)
     */
    suspend fun getVideoDetail(bvid: String): Response<VideoDetailResponse> {
        // Ensure WBI keys are initialized
        if (!WbiSignature.isInitialized()) {
            if (!initWbiKeys()) {
                throw IllegalStateException("无法初始化WBI签名，请检查网络连接")
            }
        }

        val params = mapOf(
            "bvid" to bvid
        )

        val signedParams = WbiSignature.signParams(params)

        return throttle { api.getVideoDetail(signedParams) }
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
            if (!initWbiKeys()) {
                throw IllegalStateException("无法初始化WBI签名，请检查网络连接")
            }
        }

        val params = mapOf(
            "cid" to cid.toString(),
            "bvid" to bvid,
            "qn" to quality.toString(),
            "fnval" to AudioQuality.DASH_FLAGS.toString(),
            "fnver" to "0",
            "fourk" to "1"
        )

        // Sign the parameters with WBI (without URL encoding)
        val signedParams = WbiSignature.signParams(params)

        return throttle { api.getPlayUrl(signedParams) }
    }

    /** Reuse a CID normally; refresh it once only when an old identifier is rejected. */
    suspend fun getPlayUrlWithFreshCidFallback(cid: Long, bvid: String, allowRefresh: Boolean): ResolvedPlayback {
        val response = getPlayUrl(cid, bvid)
        if (allowRefresh && response.isSuccessful && response.body()?.code in listOf(-400, -404)) {
            val detail = getVideoDetail(bvid)
            val freshCid = detail.body()?.data?.cid
            if (detail.isSuccessful && detail.body()?.code == 0 && freshCid != null && freshCid > 0 && freshCid != cid) {
                Log.i(TAG, "Cached CID changed; refreshing playback for $bvid")
                return ResolvedPlayback(freshCid, getPlayUrl(freshCid, bvid))
            }
        }
        return ResolvedPlayback(cid, response)
    }

    /**
     * Get all videos from a favorite folder (handles pagination)
     */
    suspend fun getAllFavoriteVideos(mediaId: Long): List<FavoriteMedia> {
        val allVideos = mutableListOf<FavoriteMedia>()
        var currentPage = 1
        var totalCount = 0
        val pageSize = 20
        val maxPages = 100 // Safety limit to prevent infinite loops

        try {
            do {
                val response = getFavoriteResources(mediaId, currentPage, pageSize)
                if (response.isSuccessful && response.body()?.code == 0) {
                    val data = response.body()?.data
                    if (data != null) {
                        totalCount = data.info.mediaCount
                        val medias = data.medias
                        if (medias.isNullOrEmpty()) {
                            // No more media on this page — stop even if totalCount not reached
                            break
                        }
                        allVideos.addAll(medias)
                        currentPage++
                    } else {
                        break
                    }
                } else {
                    Log.e(TAG, "Failed to get favorite resources: ${response.code()}")
                    break
                }
            } while (allVideos.size < totalCount && currentPage <= maxPages)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all favorite videos", e)
        }

        return allVideos
    }

    /**
     * Hi-Res and Dolby are separate DASH fields; ordinary AAC is the fallback.
     */
    fun selectBestAudioStream(dash: DashInfo?, allowDolby: Boolean = true): DashStream? {
        return AudioQuality.select(dash, allowDolby)?.also {
            Log.d(TAG, "Selected audio: ${AudioQuality.label(it)}, ID=${it.id}")
        }
    }

    companion object {
        private const val TAG = "BiliFavoriteRepository"
        private val wbiInitMutex = Mutex()
    }
}

/** A shared initialization failure must never mark every song as unavailable. */
class WbiInitializationException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

data class ResolvedPlayback(val cid: Long, val response: Response<PlayUrlResponse>)
