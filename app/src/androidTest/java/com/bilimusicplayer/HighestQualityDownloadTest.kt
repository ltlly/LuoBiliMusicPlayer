package com.bilimusicplayer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.DownloadStatus
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.service.download.AudioDownloadWorker
import com.bilimusicplayer.service.download.DownloadedAudioInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Requires a logged-in VIP device. Creates and removes only its own temporary song. */
@RunWith(AndroidJUnit4::class)
class HighestQualityDownloadTest {
    @Test fun workerRefreshesOldUrlAndSavesOriginalFlacTrack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getDatabase(context)
        val manager = WorkManager.getInstance(context)
        val id = "quality_probe_${UUID.randomUUID()}"
        val title = "Codex_${id}"
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "BiliMusic/$title.m4a")
        assertFalse(file.exists())
        val song = Song(id = id, title = title, artist = "音质验证", duration = 1,
            coverUrl = "", cid = 773130617, bvid = "BV1fB4y1h76Z", aid = 0,
            uploaderId = 0, uploaderName = "", pubDate = 0)
        val request = OneTimeWorkRequestBuilder<AudioDownloadWorker>().setInputData(workDataOf(
            AudioDownloadWorker.KEY_SONG_ID to id,
            AudioDownloadWorker.KEY_TITLE to title,
            // A worker that still trusts preselected URLs must fail this test.
            AudioDownloadWorker.KEY_AUDIO_URL to "https://example.invalid/expired-aac"
        )).build()
        try {
            db.songDao().insertSong(song)
            db.downloadDao().insertDownload(Download(id, title, "音质验证", DownloadStatus.QUEUED, audioUrl = "https://example.invalid/expired-aac"))
            manager.enqueue(request).result.get(30, TimeUnit.SECONDS)
            val deadline = System.currentTimeMillis() + 180_000
            var state: WorkInfo.State? = null
            while (System.currentTimeMillis() < deadline) {
                state = manager.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)?.state
                if (state?.isFinished == true) break
                delay(500)
            }
            assertEquals(db.downloadDao().getDownloadBySongId(id)?.errorMessage, WorkInfo.State.SUCCEEDED, state)
            assertTrue(file.length() > 0)
            assertEquals("Hi-Res 无损 · FLAC", DownloadedAudioInfo(context).label(file.absolutePath, file.length()))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                assertTrue((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/flac"
                })
            } finally { extractor.release() }
            assertTrue(db.songDao().getSongById(id)?.isDownloaded == true)
        } finally {
            manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
            if (file.exists()) assertTrue("测试文件清理失败", file.delete())
            db.downloadDao().deleteDownloadBySongId(id)
            db.songDao().deleteSongById(id)
        }
    }
}
