package com.bilimusicplayer

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit
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
class BiliMusicApplication : Application(), ImageLoaderFactory {

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

    /**
     * 全局 Coil ImageLoader：封面加载统一配置。
     * - 磁盘缓存 256MB（默认只有 2% 磁盘且上限 250MB，这里显式固定）
     * - 内存缓存 20%
     * - 图片请求并发限制 8（OkHttp dispatcher），避免快速滑动时瞬时打满 B站 CDN 连接
     */
    override fun newImageLoader(): ImageLoader {
        val dispatcher = Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 8
        }
        val okHttp = okhttp3.OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(okHttp)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
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
