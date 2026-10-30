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
     * Get all resource ids in a favorite folder in ONE request (no pagination).
     * GET https://api.bilibili.com/x/v3/fav/resource/ids
     */
    @GET("x/v3/fav/resource/ids")
    suspend fun getFavoriteResourceIds(
        @QueryMap params: Map<String, String>
    ): Response<FavResourceIdsResponse>

    /**
     * Batch-fetch media metadata for ids returned by resource/ids.
     * resources={id}:{type},{id}:{type},... — 固定每批20条。
     * GET https://api.bilibili.com/x/v3/fav/resource/infos
     */
    @GET("x/v3/fav/resource/infos")
    suspend fun getFavoriteResourceInfos(
        @QueryMap params: Map<String, String>
    ): Response<FavResourceInfosResponse>

    /**
     * Get play URL — 免WBI取流接口（bilimiao等三方客户端同款），
     * 与 wbi/playurl 不同风控池，连播时更不容易触发 -352/412。
     * GET https://api.bilibili.com/x/player/playurl
     */
    @GET("x/player/playurl")
    suspend fun getPlayUrl(
        @QueryMap params: Map<String, String>
    ): Response<PlayUrlResponse>

    /**
     * WBI 签名版取流接口（兜底）。
     * GET https://api.bilibili.com/x/player/wbi/playurl
     */
    @GET("x/player/wbi/playurl")
    suspend fun getPlayUrlWbi(
        @QueryMap params: Map<String, String>
    ): Response<PlayUrlResponse>
}
