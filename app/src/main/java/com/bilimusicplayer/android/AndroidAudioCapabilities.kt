package com.bilimusicplayer.android

import android.content.Context
import com.bilimusicplayer.platform.AudioCapabilities
import com.bilimusicplayer.service.AudioPlaybackCapabilities
import com.bilimusicplayer.service.download.DownloadedAudioInfo
import kotlinx.coroutines.flow.Flow

class AndroidAudioCapabilities(context: Context) : AudioCapabilities {
    private val qualityInfo = DownloadedAudioInfo(context)
    override val supportsDolby: Boolean get() = AudioPlaybackCapabilities.supportsDolby
    override fun observeQualityLabel(path: String?): Flow<String?> = qualityInfo.observeLabel(path)
}
