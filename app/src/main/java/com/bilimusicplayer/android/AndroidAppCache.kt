package com.bilimusicplayer.android

import android.content.Context
import coil3.SingletonImageLoader
import com.bilimusicplayer.platform.AppCache
import com.bilimusicplayer.service.MusicPlaybackService

class AndroidAppCache(private val context: Context) : AppCache {
    override suspend fun sizeBytes(): Long {
        val image = SingletonImageLoader.get(context).diskCache?.size ?: 0L
        return MusicPlaybackService.cacheBytes(context) + image
    }

    override suspend fun clear() {
        MusicPlaybackService.clearPlaybackCache(context)
        SingletonImageLoader.get(context).diskCache?.clear()
        SingletonImageLoader.get(context).memoryCache?.clear()
    }
}
