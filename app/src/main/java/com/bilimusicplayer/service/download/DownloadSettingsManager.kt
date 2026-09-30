package com.bilimusicplayer.service.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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
    private val API_RATE_LIMIT_ENABLED_KEY = booleanPreferencesKey("api_rate_limit_enabled")
    val apiRateLimitEnabled: Flow<Boolean> = context.downloadSettingsDataStore.data.map {
        it[API_RATE_LIMIT_ENABLED_KEY] ?: false
    }
    suspend fun setApiRateLimitEnabled(enabled: Boolean) {
        context.downloadSettingsDataStore.edit { it[API_RATE_LIMIT_ENABLED_KEY] = enabled }
    }
    /** Disabled by default; preserves the chosen rate for a later explicit re-enable. */
    suspend fun getApiRequestIntervalMs(): Long {
        val preferences = context.downloadSettingsDataStore.data.first()
        if (preferences[API_RATE_LIMIT_ENABLED_KEY] != true) return 0L
        return 60_000L / (preferences[API_RATE_LIMIT_KEY] ?: DEFAULT_API_RATE_LIMIT)
            .coerceIn(MIN_API_RATE_LIMIT, MAX_API_RATE_LIMIT)
    }

    private val WIFI_ONLY_KEY = booleanPreferencesKey("wifi_only")
    val wifiOnly: Flow<Boolean> = context.downloadSettingsDataStore.data.map { it[WIFI_ONLY_KEY] ?: false }
    suspend fun getWifiOnly() = wifiOnly.first()
    suspend fun setWifiOnly(value: Boolean) { context.downloadSettingsDataStore.edit { it[WIFI_ONLY_KEY] = value } }

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
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 8

        const val DEFAULT_API_RATE_LIMIT = 20   // Used only when explicitly enabled.
        const val MIN_API_RATE_LIMIT = 10        // minimum 10 req/min
        const val MAX_API_RATE_LIMIT = 60        // maximum 60 req/min
    }
}

