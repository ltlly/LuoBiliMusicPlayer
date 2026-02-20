package com.bilimusicplayer.service.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.downloadSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "download_settings"
)

/**
 * Manager for download-related settings
 * Persisted using DataStore preferences
 */
class DownloadSettingsManager(private val context: Context) {

    private val MAX_CONCURRENT_DOWNLOADS_KEY = intPreferencesKey("max_concurrent_downloads")
    private val API_RATE_LIMIT_KEY = intPreferencesKey("api_rate_limit_per_minute")

    /**
     * Observe max concurrent downloads setting as Flow
     */
    val maxConcurrentDownloads: Flow<Int> = context.downloadSettingsDataStore.data.map { preferences ->
        preferences[MAX_CONCURRENT_DOWNLOADS_KEY] ?: DEFAULT_MAX_CONCURRENT
    }

    /**
     * Observe API rate limit (requests per minute) as Flow
     */
    val apiRateLimit: Flow<Int> = context.downloadSettingsDataStore.data.map { preferences ->
        preferences[API_RATE_LIMIT_KEY] ?: DEFAULT_API_RATE_LIMIT
    }

    /**
     * Get current max concurrent downloads value (blocking/suspending)
     */
    suspend fun getMaxConcurrentDownloads(): Int {
        return maxConcurrentDownloads.first()
    }

    /**
     * Get current API rate limit (requests per minute)
     */
    suspend fun getApiRateLimit(): Int {
        return apiRateLimit.first()
    }

    /**
     * Calculate delay in milliseconds between API requests based on rate limit
     */
    suspend fun getApiDelayMs(): Long {
        val rateLimit = getApiRateLimit()
        // Each song needs 2 API calls (getVideoDetail + getPlayUrl)
        // So for N requests/min, we can process N/2 songs/min
        // Delay between songs = 60000 / (N/2) = 120000 / N ms
        return (120_000L / rateLimit).coerceAtLeast(500)
    }

    /**
     * Set max concurrent downloads
     */
    suspend fun setMaxConcurrentDownloads(count: Int) {
        val value = count.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)
        context.downloadSettingsDataStore.edit { preferences ->
            preferences[MAX_CONCURRENT_DOWNLOADS_KEY] = value
        }
    }

    /**
     * Set API rate limit (requests per minute)
     */
    suspend fun setApiRateLimit(requestsPerMinute: Int) {
        val value = requestsPerMinute.coerceIn(MIN_API_RATE_LIMIT, MAX_API_RATE_LIMIT)
        context.downloadSettingsDataStore.edit { preferences ->
            preferences[API_RATE_LIMIT_KEY] = value
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 4
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 8

        const val DEFAULT_API_RATE_LIMIT = 30   // 30 requests per minute
        const val MIN_API_RATE_LIMIT = 10        // minimum 10 req/min
        const val MAX_API_RATE_LIMIT = 60        // maximum 60 req/min
    }
}

