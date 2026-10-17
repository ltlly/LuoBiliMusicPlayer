package com.bilimusicplayer.network.bilibili

import com.bilimusicplayer.network.bilibili.auth.LoginStatusResponse
import retrofit2.Response
import retrofit2.http.GET

/**
 * Bilibili General API Interface
 */
interface BiliApi {

    /**
     * Check login status and get user info
     * GET https://api.bilibili.com/x/web-interface/nav
     */
    @GET("x/web-interface/nav")
    suspend fun getLoginStatus(): Response<LoginStatusResponse>
}
