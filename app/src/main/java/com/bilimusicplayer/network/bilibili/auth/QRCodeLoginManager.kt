package com.bilimusicplayer.network.bilibili.auth

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages QR Code login flow
 */
class QRCodeLoginManager(
    private val authRepository: BiliAuthRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _qrCodeUrl = MutableStateFlow<String?>(null)
    val qrCodeUrl: StateFlow<String?> = _qrCodeUrl.asStateFlow()

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState.asStateFlow()

    private var pollJob: Job? = null
    private var authCode: String? = null
    private var expireTime: Long = 0

    sealed class LoginState {
        object Idle : LoginState()
        object GeneratingQR : LoginState()
        object WaitingForScan : LoginState()
        object Success : LoginState()
        data class Error(val message: String) : LoginState()
        object Expired : LoginState()
    }

    /**
     * Start QR code login flow
     */
    suspend fun startQRLogin() {
        _loginState.value = LoginState.GeneratingQR

        try {
            // Request QR code
            val params = mapOf(
                "appkey" to BiliSignature.APP_KEY,
                "local_id" to "0",
                "ts" to "0"
            )
            val sign = BiliSignature.signBody(params)

            val response = authRepository.getQRCode(
                appkey = BiliSignature.APP_KEY,
                localId = "0",
                timestamp = 0,
                sign = sign
            )

            if (response.isSuccessful && response.body()?.code == 0) {
                val data = response.body()?.data
                if (data != null) {
                    _qrCodeUrl.value = data.url
                    authCode = data.authCode
                    expireTime = System.currentTimeMillis() + 180_000 // 180 seconds
                    _loginState.value = LoginState.WaitingForScan
                    startPolling()
                } else {
                    _loginState.value = LoginState.Error("Failed to get QR code data")
                }
            } else {
                _loginState.value = LoginState.Error("Failed to generate QR code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating QR code", e)
            _loginState.value = LoginState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Start polling for QR code scan
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && System.currentTimeMillis() < expireTime) {
                delay(4000) // Poll every 4 seconds

                if (authCode == null) break

                try {
                    val params = mapOf(
                        "appkey" to BiliSignature.APP_KEY,
                        "auth_code" to authCode!!,
                        "local_id" to "0",
                        "ts" to "0"
                    )
                    val sign = BiliSignature.signBody(params)

                    val response = authRepository.pollQRCode(
                        appkey = BiliSignature.APP_KEY,
                        authCode = authCode!!,
                        localId = "0",
                        timestamp = 0,
                        sign = sign
                    )

                    Log.d(TAG, "Poll response: isSuccessful=${response.isSuccessful}, code=${response.body()?.code}, message=${response.body()?.message}")

                    if (response.isSuccessful && response.body()?.code == 0) {
                        val data = response.body()?.data
                        Log.d(TAG, "Login data received: $data")
                        if (data != null) {
                            // Save tokens and cookies
                            authRepository.saveTokens(
                                accessToken = data.accessToken,
                                refreshToken = data.refreshToken
                            )
                            Log.d(TAG, "Tokens saved")

                            authRepository.saveCookies(data.cookieInfo.cookies)
                            Log.d(TAG, "Cookies saved: ${data.cookieInfo.cookies.size} cookies")

                            // Fetch and save user info
                            delay(1000) // Wait a bit for cookies to be set
                            val userInfoSaved = authRepository.fetchAndSaveUserInfo()
                            Log.d(TAG, "User info saved: $userInfoSaved")

                            _loginState.value = LoginState.Success
                            stopPolling()
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error polling QR code", e)
                }
            }

            // Check if expired
            if (System.currentTimeMillis() >= expireTime) {
                _loginState.value = LoginState.Expired
                stopPolling()
            }
        }
    }

    /**
     * Stop polling
     */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Reset login state
     */
    fun reset() {
        stopPolling()
        _qrCodeUrl.value = null
        _loginState.value = LoginState.Idle
        authCode = null
        expireTime = 0
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopPolling()
        scope.cancel()
    }

    companion object {
        private const val TAG = "QRCodeLoginManager"
    }
}
