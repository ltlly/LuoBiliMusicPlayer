package com.bilimusicplayer

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.bilimusicplayer.android.AndroidAppContainer
import com.bilimusicplayer.core.initShared
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.auth.BiliAuthRepository
import com.bilimusicplayer.network.interceptor.PersistentCookieJar
import com.bilimusicplayer.settings.AppStorage
import com.bilimusicplayer.settings.createSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class BiliMusicApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var container: AndroidAppContainer
        private set

    val storage: AppStorage get() = container.storage

    override fun onCreate() {
        super.onCreate()
        instance = this
        initShared(this)

        val storage = AppStorage(createSettings())
        migrateLegacyCookies(storage)
        RetrofitClient.init(storage)

        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory()) }
                .memoryCache {
                    MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                        .maxSizeBytes(128L * 1024 * 1024)
                        .build()
                }
                .build()
        }

        container = AndroidAppContainer(this, storage, applicationScope)
        applicationScope.launch { container.player.initialize() }
        if (!storage.accessToken.isNullOrEmpty()) {
            applicationScope.launch {
                runCatching {
                    BiliAuthRepository(
                        api = RetrofitClient.biliAuthApi,
                        cookieJar = RetrofitClient.getCookieJar(),
                        biliApi = RetrofitClient.biliApi,
                        storage = storage,
                    ).fetchAndSaveUserInfo()
                }
            }
        }
    }

    /**
     * Earlier releases kept cookies in a per-host SharedPreferences file. Move
     * them once so existing users stay logged in after the KMP migration.
     */
    private fun migrateLegacyCookies(storage: AppStorage) {
        if (storage.cookieStoreJson() != null) return
        val legacy = getSharedPreferences("cookies", MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val gson = Gson()
        val type = object : TypeToken<List<PersistentCookieJar.SerializableCookie>>() {}.type
        val migrated = mutableMapOf<String, List<PersistentCookieJar.SerializableCookie>>()
        legacy.all.forEach { (host, value) ->
            if (value is String) {
                runCatching { migrated[host] = gson.fromJson(value, type) }
            }
        }
        if (migrated.isNotEmpty()) {
            storage.saveCookieStoreJson(gson.toJson(migrated))
            // Existing cookie sessions have no refresh token; the sentinel keeps
            // isLoggedIn() true while Bilibili cookies remain valid.
            storage.accessToken = "cookie-session"
            storage.refreshToken = "cookie-session"
        }
    }

    companion object {
        lateinit var instance: BiliMusicApplication
            private set
    }
}