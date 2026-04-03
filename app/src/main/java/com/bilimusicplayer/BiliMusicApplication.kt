package com.bilimusicplayer

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.service.MusicPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.appPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/**
 * Application class for BiliMusicPlayer
 */
class BiliMusicApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        lateinit var instance: BiliMusicApplication
            private set

        val musicPlayerController: MusicPlayerController by lazy {
            MusicPlayerController(instance)
        }

        private val LAST_FOLDER_ID = longPreferencesKey("last_folder_id")
        private val LAST_FOLDER_TITLE = stringPreferencesKey("last_folder_title")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Retrofit client with context
        RetrofitClient.init(this)

        // Initialize music player controller
        applicationScope.launch {
            musicPlayerController.initialize()
        }
    }

    /**
     * Save the last opened favorite folder for quick resume on next launch
     */
    suspend fun saveLastFolder(folderId: Long, folderTitle: String) {
        appPrefsDataStore.edit { prefs ->
            prefs[LAST_FOLDER_ID] = folderId
            prefs[LAST_FOLDER_TITLE] = folderTitle
        }
    }

    /**
     * Get the last opened favorite folder, or null if none saved
     */
    suspend fun getLastFolder(): Pair<Long, String>? {
        val prefs = appPrefsDataStore.data.first()
        val folderId = prefs[LAST_FOLDER_ID] ?: return null
        val folderTitle = prefs[LAST_FOLDER_TITLE] ?: return null
        return Pair(folderId, folderTitle)
    }
}
