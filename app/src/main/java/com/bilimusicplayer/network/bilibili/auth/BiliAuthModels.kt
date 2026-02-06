package com.bilimusicplayer.network.bilibili.auth

import com.google.gson.annotations.SerializedName

/**
 * QR Code request response
 */
data class QRCodeResponse(
    val code: Int,
    val message: String,
    val data: QRCodeData
)

data class QRCodeData(
    val url: String,
    @SerializedName("auth_code")
    val authCode: String
)

/**
 * QR Code poll response
 */
data class QRCodePollResponse(
    val code: Int,
    val message: String,
    val data: QRCodePollData?
)

data class QRCodePollData(
    @SerializedName("access_token")
    val accessToken: String,
    @SerializedName("refresh_token")
    val refreshToken: String,
    @SerializedName("cookie_info")
    val cookieInfo: CookieInfo
)

data class CookieInfo(
    val cookies: List<CookieEntry>
)

data class CookieEntry(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    @SerializedName("expires")
    val expiresAt: Long? = null
)

/**
 * Login status response
 */
data class LoginStatusResponse(
    val code: Int,
    val message: String,
    val data: LoginStatusData?
)

data class LoginStatusData(
    val isLogin: Boolean,
    val uname: String? = null,
    val mid: Long? = null,
    val face: String? = null
)

/**
 * User login info
 */
data class LoginInfo(
    val name: String,
    val id: Long,
    val avatar: String
)

/**
 * Token refresh response
 */
data class TokenRefreshResponse(
    val code: Int,
    val message: String,
    val data: TokenRefreshData?
)

data class TokenRefreshData(
    @SerializedName("token_info")
    val tokenInfo: TokenInfo,
    @SerializedName("cookie_info")
    val cookieInfo: CookieInfo
)

data class TokenInfo(
    @SerializedName("access_token")
    val accessToken: String,
    @SerializedName("refresh_token")
    val refreshToken: String,
    @SerializedName("expires_in")
    val expiresIn: Long
)
