package com.bilimusicplayer.network.bilibili.auth

import retrofit2.Response
import retrofit2.http.*

/**
 * Bilibili Authentication API Interface
 */
interface BiliAuthApi {

    /**
     * Request QR code for TV/App login
     * POST https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code
     */
    @FormUrlEncoded
    @POST("x/passport-tv-login/qrcode/auth_code")
    suspend fun getQRCode(
        @Field("appkey") appkey: String,
        @Field("local_id") localId: String,
        @Field("ts") timestamp: Long,
        @Field("sign") sign: String
    ): Response<QRCodeResponse>

    /**
     * Poll QR code login status
     * POST https://passport.bilibili.com/x/passport-tv-login/qrcode/poll
     */
    @FormUrlEncoded
    @POST("x/passport-tv-login/qrcode/poll")
    suspend fun pollQRCode(
        @Field("appkey") appkey: String,
        @Field("auth_code") authCode: String,
        @Field("local_id") localId: String,
        @Field("ts") timestamp: Long,
        @Field("sign") sign: String
    ): Response<QRCodePollResponse>

    /**
     * Refresh access token
     * POST https://passport.bilibili.com/x/passport-login/oauth2/refresh_token
     */
    @FormUrlEncoded
    @POST("x/passport-login/oauth2/refresh_token")
    suspend fun refreshToken(
        @Field("access_key") accessKey: String,
        @Field("actionKey") actionKey: String,
        @Field("appkey") appkey: String,
        @Field("refresh_token") refreshToken: String,
        @Field("ts") timestamp: Long,
        @Field("sign") sign: String
    ): Response<TokenRefreshResponse>
}
