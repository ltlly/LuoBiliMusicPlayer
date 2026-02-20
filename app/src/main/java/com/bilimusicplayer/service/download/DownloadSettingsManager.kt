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

    /**
     * Observe max concurrent downloads setting as Flow
     */
    val maxConcurrentDownloads: Flow<Int> = context.downloadSettingsDataStore.data.map { preferences ->
        preferences[MAX_CONCURRENT_DOWNLOADS_KEY] ?: DEFAULT_MAX_CONCURRENT
    }

    /**
     * Get current max concurrent downloads value (blocking/suspending)
     */
    suspend fun getMaxConcurrentDownloads(): Int {
        return maxConcurrentDownloads.first()
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

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 4
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 8
    }
}

