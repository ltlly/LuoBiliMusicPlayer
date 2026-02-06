package com.bilimusicplayer

import android.app.Application
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.service.MusicPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class for BiliMusicPlayer
 */
class BiliMusicApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        lateinit var instance: BiliMusicApplication
            private set

        val musicPlayerController: MusicPlayerController by lazy {
            MusicPlayerController(instance)
        }
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
}
