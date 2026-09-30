package com.bilimusicplayer.service

import android.media.MediaCodecList

object AudioPlaybackCapabilities {
    // Downloading retains Dolby even on devices without a decoder. Online playback
    // falls back to AAC there so the highest-quality policy never makes it unplayable.
    val supportsDolby: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
                !codec.isEncoder && codec.supportedTypes.any {
                    it.equals("audio/eac3", true) || it.equals("audio/eac3-joc", true)
                }
            }
        }.getOrDefault(false)
    }
}
