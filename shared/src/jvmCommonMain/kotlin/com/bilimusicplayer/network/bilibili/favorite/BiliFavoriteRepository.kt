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
     * Get favorite folder contents — 默认 APP 签名，风控响应回退 WBI 签名。
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

        // 默认通道：APP签名（无需先调 nav 初始化，且与 WBI 是不同风控计数）
        val response = throttle { api.getFavoriteResources(AppSignature.signParams(params)) }
        val code = response.body()?.code
        if (response.isSuccessful && code !in listOf(-352, -403, -412)) return response

        // 兜底通道：WBI 签名
        Log.w(TAG, "APP签名通道失败(HTTP ${response.code()}, code=$code)，回退WBI")
        if (!WbiSignature.isInitialized() && !initWbiKeys()) return response
        return throttle { api.getFavoriteResources(WbiSignature.signParams(params)) }
    }

    /**
     * Get video detail by BVID — 默认 APP 签名，风控响应回退 WBI 签名
     */
    suspend fun getVideoDetail(bvid: String): Response<VideoDetailResponse> {
        val params = mapOf("bvid" to bvid)

        val response = throttle { api.getVideoDetail(AppSignature.signParams(params)) }
        val code = response.body()?.code
        if (response.isSuccessful && code !in listOf(-352, -403, -412)) return response

        Log.w(TAG, "view接口APP签名失败(HTTP ${response.code()}, code=$code)，回退WBI")
        if (!WbiSignature.isInitialized() && !initWbiKeys()) return response
        return throttle { api.getVideoDetail(WbiSignature.signParams(params)) }
    }

    /**
     * Get all resource ids in a folder — 1 request regardless of folder size.
     * 收藏夹有几十页时用它替代逐页翻请求，请求数从 N 页降到 1 次。
     * 默认 APP 签名，风控响应回退 WBI 签名。
     */
    suspend fun getFavoriteResourceIds(mediaId: Long): List<FavResourceId> {
        val params = mapOf(
            "media_id" to mediaId.toString(),
            "platform" to "web"
        )

        var response = throttle { api.getFavoriteResourceIds(AppSignature.signParams(params)) }
        var code = response.body()?.code
        if (!response.isSuccessful || code in listOf(-352, -403, -412)) {
            Log.w(TAG, "ids接口APP签名失败(HTTP ${response.code()}, code=$code)，回退WBI")
            if (WbiSignature.isInitialized() || initWbiKeys()) {
                response = throttle { api.getFavoriteResourceIds(WbiSignature.signParams(params)) }
                code = response.body()?.code
            }
        }
        if (!response.isSuccessful || code != 0) {
            throw java.io.IOException("获取收藏夹ID列表失败: HTTP ${response.code()}, code=$code")
        }
        return response.body()?.data ?: emptyList()
    }

    /**
     * 单块请求 resource/infos（至多20条）：APP签名默认，风控码 -352/-403/-412
     * 或 HTTP 失败回退 WBI 签名；最终 HTTP 失败或 code!=0 返回 null，
     * 表示"接口失败"（区别于"返回条目少"——失效视频本就不会返回）。
     */
    suspend fun getFavoriteResourceInfosChunk(chunk: List<FavResourceId>): List<FavoriteMedia>? {
        if (chunk.isEmpty()) return emptyList()
        val params = mapOf(
            "resources" to chunk.joinToString(",") { "${it.id}:${it.type}" },
            "platform" to "web"
        )
        var response = throttle { api.getFavoriteResourceInfos(AppSignature.signParams(params)) }
        var code = response.body()?.code
        if (!response.isSuccessful || code in listOf(-352, -403, -412)) {
            Log.w(TAG, "infos接口APP签名失败(HTTP ${response.code()}, code=$code)，回退WBI")
            if (WbiSignature.isInitialized() || initWbiKeys()) {
                response = throttle { api.getFavoriteResourceInfos(WbiSignature.signParams(params)) }
                code = response.body()?.code
            }
        }
        if (!response.isSuccessful || code != 0) {
            Log.w(TAG, "infos接口失败: HTTP ${response.code()}, code=$code")
            return null
        }
        return response.body()?.data.orEmpty()
    }

    /**
     * 批量补全收藏夹条目元数据：resources={id}:{type},... 固定20条一批。
     * 遇到接口失败（null）就停止并返回已得结果（不抛，让上层下次再补）。
     */
    suspend fun getFavoriteResourceInfos(ids: List<FavResourceId>): List<FavoriteMedia> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<FavoriteMedia>()
        for (chunk in ids.chunked(RESOURCE_INFOS_BATCH)) {
            result += getFavoriteResourceInfosChunk(chunk) ?: break
        }
        return result
    }

    /**
     * Get play URL — 优先免WBI接口（x/player/playurl），失败后回退WBI接口。
     * 免WBI接口与收藏夹翻页走不同的风控计数，连播时更耐限流。
     * @param cid Video CID
     * @param bvid Video BVID
     * @param quality Video quality (default 127 to request highest available quality)
     */
    suspend fun getPlayUrl(
        cid: Long,
        bvid: String,
        quality: Int = 127  // Request highest quality, actual quality depends on video source and account level
    ): Response<PlayUrlResponse> {
        val params = mapOf(
            "cid" to cid.toString(),
            "bvid" to bvid,
            "qn" to quality.toString(),
            "fnval" to AudioQuality.DASH_FLAGS.toString(),
            "fnver" to "0",
            "fourk" to "1",
            "type" to "",
            "otype" to "json"
        )

        // Primary: non-WBI endpoint, APP签名（默认算法）
        val response = throttle { api.getPlayUrl(AppSignature.signParams(params)) }
        if (response.isSuccessful && response.body()?.code == 0) return response

        // Fallback: WBI-signed endpoint — 仅在接口级失败/风控时重试；
        // -404 等业务错误原样返回，交给 CID 刷新逻辑处理
        val apiCode = response.body()?.code
        if (response.isSuccessful && apiCode !in listOf(-352, -400, -403, -412)) return response
        if (!WbiSignature.isInitialized() && !initWbiKeys()) return response
        return throttle { api.getPlayUrlWbi(WbiSignature.signParams(params)) }
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
        private const val RESOURCE_INFOS_BATCH = 20
        private val wbiInitMutex = Mutex()
    }
}

/** A shared initialization failure must never mark every song as unavailable. */
class WbiInitializationException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

data class ResolvedPlayback(val cid: Long, val response: Response<PlayUrlResponse>)
