package com.bilimusicplayer.network.bilibili.auth

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bilimusicplayer.network.interceptor.PersistentCookieJar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response

private val Context.authDataStore by preferencesDataStore(name = "bili_auth")

/**
 * Repository for Bilibili authentication
 */
class BiliAuthRepository(
    private val context: Context,
    private val api: BiliAuthApi,
    private val cookieJar: CookieJar,
    private val biliApi: com.bilimusicplayer.network.bilibili.BiliApi
) {
    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val userIdKey = stringPreferencesKey("user_id")
    private val usernameKey = stringPreferencesKey("username")
    private val avatarKey = stringPreferencesKey("avatar")

    /**
     * Get QR code for login
     */
    suspend fun getQRCode(
        appkey: String,
        localId: String,
        timestamp: Long,
        sign: String
    ): Response<QRCodeResponse> {
        return api.getQRCode(appkey, localId, timestamp, sign)
    }

    /**
     * Poll QR code status
     */
    suspend fun pollQRCode(
        appkey: String,
        authCode: String,
        localId: String,
        timestamp: Long,
        sign: String
    ): Response<QRCodePollResponse> {
        return api.pollQRCode(appkey, authCode, localId, timestamp, sign)
    }

    /**
     * Check login status
     */
    suspend fun getLoginStatus(): Response<LoginStatusResponse> {
        return biliApi.getLoginStatus()
    }

    /**
     * Refresh access token
     */
    suspend fun refreshToken(): Response<TokenRefreshResponse>? {
        val tokens = getTokens().first()
        val accessToken = tokens.first
        val refreshToken = tokens.second

        if (accessToken.isNullOrEmpty() || refreshToken.isNullOrEmpty()) {
            return null
        }

        val params = mapOf(
            "access_key" to accessToken,
            "actionKey" to "appkey",
            "appkey" to BiliSignature.APP_KEY,
            "refresh_token" to refreshToken,
            "ts" to "0"
        )
        val sign = BiliSignature.signBody(params)

        val response = api.refreshToken(
            accessKey = accessToken,
            actionKey = "appkey",
            appkey = BiliSignature.APP_KEY,
            refreshToken = refreshToken,
            timestamp = 0,
            sign = sign
        )

        // Save new tokens if refresh successful
        if (response.isSuccessful && response.body()?.code == 0) {
            response.body()?.data?.let { data ->
                saveTokens(
                    accessToken = data.tokenInfo.accessToken,
                    refreshToken = data.tokenInfo.refreshToken
                )
                saveCookies(data.cookieInfo.cookies)
            }
        }

        return response
    }

    /**
     * Save access token and refresh token
     */
    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        context.authDataStore.edit { preferences ->
            preferences[accessTokenKey] = accessToken
            preferences[refreshTokenKey] = refreshToken
        }
    }

    /**
     * Get stored tokens
     */
    fun getTokens(): Flow<Pair<String?, String?>> {
        return context.authDataStore.data.map { preferences ->
            Pair(
                preferences[accessTokenKey],
                preferences[refreshTokenKey]
            )
        }
    }

    /**
     * Save cookies to CookieJar
     */
    fun saveCookies(cookies: List<CookieEntry>) {
        val url = "https://api.bilibili.com".toHttpUrl()
        val cookieList = cookies.map { entry ->
            Cookie.Builder()
                .name(entry.name)
                .value(entry.value)
                .domain("api.bilibili.com") // Use api.bilibili.com as the domain
                .path(entry.path ?: "/")
                .apply {
                    // Convert seconds to milliseconds
                    entry.expiresAt?.let { expiresAt(it * 1000) }
                }
                .build()
        }
        Log.d(TAG, "Saving ${cookieList.size} cookies for api.bilibili.com")
        (cookieJar as? PersistentCookieJar)?.saveFromResponse(url, cookieList)
    }

    /**
     * Get stored cookies
     */
    fun getCookies(): List<Cookie> {
        val url = "https://bilibili.com".toHttpUrl()
        return cookieJar.loadForRequest(url)
    }

    /**
     * Clear all auth data
     */
    suspend fun clearAuth() {
        context.authDataStore.edit { it.clear() }
        // Clear cookies
        val url = "https://bilibili.com".toHttpUrl()
        (cookieJar as? PersistentCookieJar)?.clear()
    }

    /**
     * Check if user is logged in
     */
    suspend fun isLoggedIn(): Boolean {
        val tokens = getTokens().first()
        return !tokens.first.isNullOrEmpty() && !tokens.second.isNullOrEmpty()
    }

    /**
     * Get user info from nav API and save it
     */
    suspend fun fetchAndSaveUserInfo(): Boolean {
        return try {
            Log.d(TAG, "Fetching user info from nav API...")
            val response = getLoginStatus()
            Log.d(TAG, "Nav API response: isSuccessful=${response.isSuccessful}, code=${response.body()?.code}")

            if (response.isSuccessful && response.body()?.code == 0) {
                val data = response.body()?.data
                Log.d(TAG, "User data: isLogin=${data?.isLogin}, mid=${data?.mid}, uname=${data?.uname}")

                if (data?.isLogin == true && data.mid != null) {
                    saveUserInfo(
                        userId = data.mid,
                        username = data.uname ?: "",
                        avatar = data.face ?: ""
                    )
                    Log.d(TAG, "User info saved successfully: ${data.uname} (${data.mid})")
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
    }

    companion object {
        private const val TAG = "BiliAuthRepository"
    }

    /**
     * Save user info
     */
    private suspend fun saveUserInfo(userId: Long, username: String, avatar: String) {
        context.authDataStore.edit { preferences ->
            preferences[userIdKey] = userId.toString()
            preferences[usernameKey] = username
            preferences[avatarKey] = avatar
        }
    }

    /**
     * Get stored user ID
     */
    suspend fun getUserId(): Long? {
        val userId = context.authDataStore.data.map { preferences ->
            preferences[userIdKey]
        }.first()
        return userId?.toLongOrNull()
    }

    /**
     * Get stored username
     */
    suspend fun getUsername(): String? {
        return context.authDataStore.data.map { preferences ->
            preferences[usernameKey]
        }.first()
    }

    /**
     * Get stored avatar URL
     */
    suspend fun getAvatar(): String? {
        return context.authDataStore.data.map { preferences ->
            preferences[avatarKey]
        }.first()
    }
}
