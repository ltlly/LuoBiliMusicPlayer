package com.bilimusicplayer.settings

import com.bilimusicplayer.core.AppPaths
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class DownloadSettings(
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    val wifiOnly: Boolean = false,
    val apiRateLimitEnabled: Boolean = false,
    val apiRateLimitPerMinute: Int = DEFAULT_API_RATE_LIMIT,
    val downloadDir: String = AppPaths.defaultDownloadDir(),
) {
    val apiRequestIntervalMs: Long
        get() = if (!apiRateLimitEnabled) 0L else 60_000L / apiRateLimitPerMinute.coerceIn(
            MIN_API_RATE_LIMIT,
            MAX_API_RATE_LIMIT,
        )

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 8
        const val DEFAULT_API_RATE_LIMIT = 20
        const val MIN_API_RATE_LIMIT = 10
        const val MAX_API_RATE_LIMIT = 60
    }
}

/**
 * Small, synchronous store for preferences, cookies and resumable playback state.
 *
 * Chatty values that the UI observes are mirrored in [StateFlow]s so Compose never
 * has to poll the disk. Cookies and the request gate stay synchronous, matching the
 * existing OkHttp interceptor contract.
 */
class AppStorage(private val settings: Settings) {

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _downloadSettings = MutableStateFlow(readDownloadSettings())
    val downloadSettings: StateFlow<DownloadSettings> = _downloadSettings.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
        _themeMode.value = mode
    }

    fun setDownloadSettings(value: DownloadSettings) {
        val normalized = value.copy(
            maxConcurrent = value.maxConcurrent.coerceIn(
                DownloadSettings.MIN_CONCURRENT,
                DownloadSettings.MAX_CONCURRENT,
            ),
            apiRateLimitPerMinute = value.apiRateLimitPerMinute.coerceIn(
                DownloadSettings.MIN_API_RATE_LIMIT,
                DownloadSettings.MAX_API_RATE_LIMIT,
            ),
        )
        settings.putInt(KEY_MAX_CONCURRENT, normalized.maxConcurrent)
        settings.putBoolean(KEY_WIFI_ONLY, normalized.wifiOnly)
        settings.putBoolean(KEY_API_RATE_ENABLED, normalized.apiRateLimitEnabled)
        settings.putInt(KEY_API_RATE_LIMIT, normalized.apiRateLimitPerMinute)
        settings.putString(KEY_DOWNLOAD_DIR, normalized.downloadDir)
        _downloadSettings.value = normalized
    }

    fun updateDownloadSettings(transform: (DownloadSettings) -> DownloadSettings) {
        setDownloadSettings(transform(_downloadSettings.value))
    }

    fun saveLastFolder(folderId: Long, title: String) {
        settings.putLong(KEY_LAST_FOLDER_ID, folderId)
        settings.putString(KEY_LAST_FOLDER_TITLE, title)
    }

    fun lastFolder(): Pair<Long, String>? {
        val id = settings.getLongOrNull(KEY_LAST_FOLDER_ID) ?: return null
        val title = settings.getStringOrNull(KEY_LAST_FOLDER_TITLE) ?: return null
        return id to title
    }

    var accessToken: String?
        get() = settings.getStringOrNull(KEY_ACCESS_TOKEN)
        set(value) = value?.let { settings.putString(KEY_ACCESS_TOKEN, it) }
            ?: settings.remove(KEY_ACCESS_TOKEN)

    var refreshToken: String?
        get() = settings.getStringOrNull(KEY_REFRESH_TOKEN)
        set(value) = value?.let { settings.putString(KEY_REFRESH_TOKEN, it) }
            ?: settings.remove(KEY_REFRESH_TOKEN)

    var userId: Long?
        get() = settings.getLongOrNull(KEY_USER_ID)
        set(value) = value?.let { settings.putLong(KEY_USER_ID, it) }
            ?: settings.remove(KEY_USER_ID)

    var username: String?
        get() = settings.getStringOrNull(KEY_USERNAME)
        set(value) = value?.let { settings.putString(KEY_USERNAME, it) }
            ?: settings.remove(KEY_USERNAME)

    var avatar: String?
        get() = settings.getStringOrNull(KEY_AVATAR)
        set(value) = value?.let { settings.putString(KEY_AVATAR, it) }
            ?: settings.remove(KEY_AVATAR)

    var gateLastRequestAt: Long
        get() = settings.getLong(KEY_GATE_LAST, 0L)
        set(value) = settings.putLong(KEY_GATE_LAST, value)

    var gateCooldownUntil: Long
        get() = settings.getLong(KEY_GATE_COOLDOWN, 0L)
        set(value) = settings.putLong(KEY_GATE_COOLDOWN, value)

    var gateLevel: Int
        get() = settings.getInt(KEY_GATE_LEVEL, 0)
        set(value) = settings.putInt(KEY_GATE_LEVEL, value)

    var playbackStateJson: String?
        get() = settings.getStringOrNull(KEY_PLAYBACK_STATE)
        set(value) = value?.let { settings.putString(KEY_PLAYBACK_STATE, it) }
            ?: settings.remove(KEY_PLAYBACK_STATE)

    fun clearRequestCooldown() {
        gateCooldownUntil = 0L
        gateLevel = 0
        gateLastRequestAt = 0L
    }

    fun cookieStoreJson(): String? = settings.getStringOrNull(KEY_COOKIES)

    fun saveCookieStoreJson(json: String) = settings.putString(KEY_COOKIES, json)

    fun clearAuth() {
        listOf(
            KEY_ACCESS_TOKEN,
            KEY_REFRESH_TOKEN,
            KEY_USER_ID,
            KEY_USERNAME,
            KEY_AVATAR,
        ).forEach(settings::remove)
    }

    private fun readThemeMode(): ThemeMode = runCatching {
        ThemeMode.valueOf(settings.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name))
    }.getOrDefault(ThemeMode.SYSTEM)

    private fun readDownloadSettings(): DownloadSettings = DownloadSettings(
        maxConcurrent = settings.getInt(KEY_MAX_CONCURRENT, DownloadSettings.DEFAULT_MAX_CONCURRENT),
        wifiOnly = settings.getBoolean(KEY_WIFI_ONLY, false),
        apiRateLimitEnabled = settings.getBoolean(KEY_API_RATE_ENABLED, false),
        apiRateLimitPerMinute = settings.getInt(KEY_API_RATE_LIMIT, DownloadSettings.DEFAULT_API_RATE_LIMIT),
        downloadDir = settings.getString(KEY_DOWNLOAD_DIR, AppPaths.defaultDownloadDir()),
    )

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_API_RATE_ENABLED = "api_rate_limit_enabled"
        const val KEY_API_RATE_LIMIT = "api_rate_limit_per_minute"
        const val KEY_DOWNLOAD_DIR = "download_dir"
        const val KEY_LAST_FOLDER_ID = "last_folder_id"
        const val KEY_LAST_FOLDER_TITLE = "last_folder_title"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_AVATAR = "avatar"
        const val KEY_GATE_LAST = "gate_last"
        const val KEY_GATE_COOLDOWN = "gate_cooldown"
        const val KEY_GATE_LEVEL = "gate_level"
        const val KEY_PLAYBACK_STATE = "playback_state"
        const val KEY_COOKIES = "cookies"
    }
}