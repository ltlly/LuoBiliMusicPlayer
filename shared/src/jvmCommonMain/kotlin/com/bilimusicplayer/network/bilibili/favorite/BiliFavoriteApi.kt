package com.bilimusicplayer.network.bilibili.favorite

import retrofit2.Response
import retrofit2.http.*

/**
 * Bilibili Favorite and Video API Interface
 */
interface BiliFavoriteApi {

    /**
     * Get navigation info (includes WBI keys)
     * GET https://api.bilibili.com/x/web-interface/nav
     */
    @GET("x/web-interface/nav")
    suspend fun getNavInfo(): Response<NavResponse>

    /**
     * Get user's favorite folders list
     * GET https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid={mid}
     */
    @GET("x/v3/fav/folder/created/list-all")
    suspend fun getFavoriteFolders(
        @Query("up_mid") userId: Long
    ): Response<FavoriteListResponse>

    /**
     * Get favorite folder contents (requires WBI signature)
     * GET https://api.bilibili.com/x/v3/fav/resource/list
     */
    @GET("x/v3/fav/resource/list")
    suspend fun getFavoriteResources(
        @QueryMap params: Map<String, String>
    ): Response<FavoriteResourceResponse>

    /**
     * Get video detail (requires WBI signature)
     * GET https://api.bilibili.com/x/web-interface/view?bvid={bvid}
     */
    @GET("x/web-interface/view")
    suspend fun getVideoDetail(
        @QueryMap params: Map<String, String>
    ): Response<VideoDetailResponse>

    /**
     * Get play URL (requires WBI signature)
     * GET https://api.bilibili.com/x/player/wbi/playurl
     */
    @GET("x/player/wbi/playurl")
    suspend fun getPlayUrl(
        @QueryMap params: Map<String, String>
    ): Response<PlayUrlResponse>
}
