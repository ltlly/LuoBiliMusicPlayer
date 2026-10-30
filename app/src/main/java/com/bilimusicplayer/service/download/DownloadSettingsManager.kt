package com.bilimusicplayer.service.download

import android.content.Context
import com.bilimusicplayer.BiliMusicApplication
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Compatibility facade over the shared AppStorage for existing Android workers.
 * The desktop implementation reads the same settings through AppStorage.
 */
class DownloadSettingsManager(private val storage: AppStorage) {

    constructor(context: Context) : this(BiliMusicApplication.instance.storage)

    val apiRateLimitEnabled: Flow<Boolean> =
        storage.downloadSettings.map { it.apiRateLimitEnabled }

    suspend fun setApiRateLimitEnabled(enabled: Boolean) {
        storage.updateDownloadSettings { it.copy(apiRateLimitEnabled = enabled) }
    }

    suspend fun getApiRequestIntervalMs(): Long = storage.downloadSettings.value.apiRequestIntervalMs

    val wifiOnly: Flow<Boolean> = storage.downloadSettings.map { it.wifiOnly }

    suspend fun getWifiOnly(): Boolean = storage.downloadSettings.value.wifiOnly

    suspend fun setWifiOnly(value: Boolean) {
        storage.updateDownloadSettings { it.copy(wifiOnly = value) }
    }

    val maxConcurrentDownloads: Flow<Int> = storage.downloadSettings.map { it.maxConcurrent }

    val apiRateLimit: Flow<Int> = storage.downloadSettings.map { it.apiRateLimitPerMinute }

    suspend fun getMaxConcurrentDownloads(): Int = storage.downloadSettings.value.maxConcurrent

    suspend fun getApiRateLimit(): Int = storage.downloadSettings.value.apiRateLimitPerMinute

    suspend fun setMaxConcurrentDownloads(count: Int) {
        storage.updateDownloadSettings { it.copy(maxConcurrent = count) }
    }

    suspend fun setApiRateLimit(requestsPerMinute: Int) {
        storage.updateDownloadSettings { it.copy(apiRateLimitPerMinute = requestsPerMinute) }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 8

        const val DEFAULT_API_RATE_LIMIT = 20
        const val MIN_API_RATE_LIMIT = 10
        const val MAX_API_RATE_LIMIT = 60
    }
}
