package com.bilimusicplayer.service.download

import android.content.Context
import com.bilimusicplayer.network.bilibili.favorite.AudioQuality
import com.bilimusicplayer.network.bilibili.favorite.DashStream
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers

/** Metadata for new downloads; old files never acquire an unverified Hi-Res badge. */
class DownloadedAudioInfo(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("download_audio_quality", Context.MODE_PRIVATE)

    fun save(file: File, stream: DashStream) {
        prefs.edit()
            .putString(file.absolutePath, AudioQuality.label(stream))
            .putLong("size:${file.absolutePath}", file.length())
            .putInt("rank:${file.absolutePath}", AudioQuality.rank(stream))
            .commit()
    }

    fun observeLabel(path: String?): Flow<String?> = callbackFlow<Unit> {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == path || key == "size:$path") trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { path?.let { label(it, File(it).length()) } }.flowOn(Dispatchers.IO).distinctUntilChanged()

    fun rank(file: File): Int = if (prefs.getLong("size:${file.absolutePath}", -1) == file.length()) prefs.getInt("rank:${file.absolutePath}", 0) else 0

    fun label(path: String?, fileSize: Long): String? {
        if (path == null || prefs.getLong("size:$path", -1) != fileSize) return null
        return prefs.getString(path, null)
    }
}
