package com.bilimusicplayer.network.bilibili.favorite

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class AudioQualityTest {
    private fun stream(id: Int, bandwidth: Int = 192000, codecs: String = "mp4a.40.2", url: String = "https://cdn.test/$id") =
        DashStream(id, url, null, bandwidth, "audio/mp4", codecs, null)
    private val aac = stream(30280)
    private val flac = stream(30251, 1440000, "fLaC")
    private val dolby = stream(30250, 448000, "ec-3")

    @Test fun losslessWinsOverSpatialAndOrdinaryEvenThoughAacHasHigherId() {
        val dash = DashInfo(1, null, listOf(aac), FlacAudio(true, flac), DolbyAudio(1, listOf(dolby)))
        assertEquals(flac, AudioQuality.select(dash))
    }
    @Test fun dolbyWinsOverHigherNumericAacId() {
        assertEquals(dolby, AudioQuality.select(DashInfo(1, null, listOf(aac), dolby = DolbyAudio(1, listOf(dolby)))))
    }
    @Test fun unsupportedDolbyFallsBackForPlaybackButRemainsDownloadable() {
        val dash = DashInfo(1, null, listOf(aac), dolby = DolbyAudio(1, listOf(dolby)))
        assertEquals(aac, AudioQuality.select(dash, allowDolby = false))
        assertEquals(dolby, AudioQuality.select(dash))
    }
    @Test fun ordinaryFallbackHandlesUnorderedTracksAndBandwidthTies() {
        val higher = aac.copy(bandwidth = 320000)
        assertEquals(higher, AudioQuality.select(DashInfo(1, null, listOf(stream(30216), higher, stream(30232), aac))))
    }
    @Test fun displayFlagDoesNotInventAnUnavailableLosslessTrack() {
        assertEquals(aac, AudioQuality.select(DashInfo(1, null, listOf(aac), FlacAudio(true, null))))
    }
    @Test fun emptyOrNullDashHasNoAudio() {
        assertNull(AudioQuality.select(null))
        assertNull(AudioQuality.select(DashInfo(1, null, emptyList())))
    }
    @Test fun blankPremiumUrlFallsBackButBackupUrlCanRescueIt() {
        val broken = flac.copy(baseUrl = "")
        assertEquals(aac, AudioQuality.select(DashInfo(1, null, listOf(aac), FlacAudio(true, broken))))
        val rescued = broken.copy(backupUrl = listOf("https://backup.test/audio"))
        assertEquals(rescued, AudioQuality.select(DashInfo(1, null, listOf(aac), FlacAudio(true, rescued))))
        assertEquals(listOf("https://backup.test/audio"), AudioQuality.urls(rescued))
    }
    @Test fun snakeCaseApiAndSeparatePremiumObjectsAreParsed() {
        val dash = Gson().fromJson("""{
          "duration":10, "audio":[{"id":30280,"base_url":"https://cdn.test/aac","bandwidth":192000}],
          "flac":{"display":true,"audio":{"id":30251,"base_url":"https://cdn.test/flac",
          "backup_url":["https://cdn.test/backup"],"bandwidth":1440000,"mime_type":"audio/mp4","codecs":"fLaC"}},
          "dolby":{"type":1,"audio":[{"id":30250,"baseUrl":"https://cdn.test/dolby","codecs":"ec-3"}]}
        }""", DashInfo::class.java)
        val selected = AudioQuality.select(dash)!!
        assertEquals(30251, selected.id)
        assertEquals("audio/mp4", selected.mimeType)
        assertEquals(2, AudioQuality.urls(selected).size)
    }
    @Test fun camelCaseAndMissingOrdinaryArrayRemainUsable() {
        val dash = Gson().fromJson("""{"flac":{"audio":{"id":30251,"baseUrl":"https://cdn.test/flac","codecs":"fLaC"}}}""", DashInfo::class.java)
        assertEquals(30251, AudioQuality.select(dash)?.id)
    }
    @Test fun normalAacIsNeverLabelledAsHiRes() {
        assertEquals("AAC · 192 kbps", AudioQuality.label(aac))
        assertEquals("Hi-Res 无损 · FLAC", AudioQuality.label(flac))
    }
    @Test fun requestFlagsIncludeDashAndDolbyAudio() {
        assertEquals(16, AudioQuality.DASH_FLAGS and 16)
        assertEquals(256, AudioQuality.DASH_FLAGS and 256)
    }
}
