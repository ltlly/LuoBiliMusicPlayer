package com.bilimusicplayer.network.bilibili.auth

import com.bilimusicplayer.core.Log
import com.bilimusicplayer.network.bilibili.BiliApi
import com.bilimusicplayer.network.interceptor.PersistentCookieJar
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response

/** Repository for Bilibili authentication. */
class BiliAuthRepository(
    private val api: BiliAuthApi,
    private val cookieJar: CookieJar,
    private val biliApi: BiliApi,
    private val storage: AppStorage,
) {

    suspend fun getQRCode(
        appkey: String,
        localId: String,
        timestamp: Long,
        sign: String,
    ): Response<QRCodeResponse> = api.getQRCode(appkey, localId, timestamp, sign)

    suspend fun pollQRCode(
        appkey: String,
        authCode: String,
        localId: String,
        timestamp: Long,
        sign: String,
    ): Response<QRCodePollResponse> = api.pollQRCode(appkey, authCode, localId, timestamp, sign)

    suspend fun getLoginStatus(): Response<LoginStatusResponse> = biliApi.getLoginStatus()

    suspend fun refreshToken(): Response<TokenRefreshResponse>? {
        val accessToken = storage.accessToken
        val refreshToken = storage.refreshToken
        if (accessToken.isNullOrEmpty() || refreshToken.isNullOrEmpty()) return null

        val params = mapOf(
            "access_key" to accessToken,
            "actionKey" to "appkey",
            "appkey" to BiliSignature.APP_KEY,
            "refresh_token" to refreshToken,
            "ts" to "0",
        )
        val sign = BiliSignature.signBody(params)

        val response = api.refreshToken(
            accessKey = accessToken,
            actionKey = "appkey",
            appkey = BiliSignature.APP_KEY,
            refreshToken = refreshToken,
            timestamp = 0,
            sign = sign,
        )

        if (response.isSuccessful && response.body()?.code == 0) {
            response.body()?.data?.let { data ->
                saveTokens(data.tokenInfo.accessToken, data.tokenInfo.refreshToken)
                saveCookies(data.cookieInfo.cookies)
            }
        }
        return response
    }

    fun saveTokens(accessToken: String, refreshToken: String) {
        storage.accessToken = accessToken
        storage.refreshToken = refreshToken
    }

    fun getTokens(): Flow<Pair<String?, String?>> = flowOf(storage.accessToken to storage.refreshToken)

    fun saveCookies(cookies: List<CookieEntry>) {
        val url = "https://api.bilibili.com".toHttpUrl()
        val cookieList = cookies.map { entry ->
            Cookie.Builder()
                .name(entry.name)
                .value(entry.value)
                .domain("api.bilibili.com")
                .path(entry.path ?: "/")
                .apply { entry.expiresAt?.let { expiresAt(it * 1000) } }
                .build()
        }
        Log.d(TAG, "Saving ${cookieList.size} cookies for api.bilibili.com")
        (cookieJar as? PersistentCookieJar)?.saveFromResponse(url, cookieList)
    }

    fun getCookies(): List<Cookie> = cookieJar.loadForRequest("https://bilibili.com".toHttpUrl())

    fun clearAuth() {
        storage.clearAuth()
        (cookieJar as? PersistentCookieJar)?.clear()
    }

    fun isLoggedIn(): Boolean {
        if (storage.accessToken.isNullOrEmpty() || storage.refreshToken.isNullOrEmpty()) return false
        // Migrated sessions only carry cookies; verify the session cookie exists.
        if (storage.accessToken == "cookie-session") {
            return cookieJar.loadForRequest("https://api.bilibili.com".toHttpUrl()).any { it.name == "SESSDATA" }
        }
        return true
    }

    suspend fun fetchAndSaveUserInfo(): Boolean = try {
        Log.d(TAG, "Fetching user info from nav API...")
        val response = getLoginStatus()
        if (response.isSuccessful && response.body()?.code == 0) {
            val data = response.body()?.data
            if (data?.isLogin == true && data.mid != null) {
                storage.userId = data.mid
                storage.username = data.uname ?: ""
                storage.avatar = data.face ?: ""
                Log.d(TAG, "User info saved: ${data.uname} (${data.mid})")
                true
            } else {
                Log.w(TAG, "User not logged in or missing mid")
                false
            }
        } else {
            Log.w(TAG, "Nav API call failed: ${response.body()?.message}")
            false
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching user info", e)
        false
    }

    fun getUserId(): Long? = storage.userId

    fun getUsername(): String? = storage.username

    fun getAvatar(): String? = storage.avatar

    private companion object {
        const val TAG = "BiliAuthRepository"
    }
}