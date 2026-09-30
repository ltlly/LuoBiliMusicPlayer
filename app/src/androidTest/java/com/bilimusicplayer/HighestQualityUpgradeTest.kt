package com.bilimusicplayer

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.favorite.*
import com.bilimusicplayer.service.download.*
import kotlinx.coroutines.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only creates a temporary probe; existing songs and settings are restored unchanged. */
@RunWith(AndroidJUnit4::class)
class HighestQualityUpgradeTest {
    @Test fun upgradesAacToFlacSkipsIdenticalAndRecoversInterruptedReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getDatabase(context)
        val work = WorkManager.getInstance(context)
        val prefs = context.getSharedPreferences("quality_upgrade", Context.MODE_PRIVATE)
        assertTrue("不要在真实升级任务运行时执行此测试", prefs.getString("status", "idle") != "running")
        val saved = prefs.all
        val id = "quality_upgrade_probe_${UUID.randomUUID()}"
        val title = "Codex_$id"
        val repo = BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)
        val source = repo.getPlayUrl(773130617, "BV1fB4y1h76Z").body()!!.data!!.dash!!
        val aac = source.audio!!.maxBy { AudioQuality.rank(it) }
        assertTrue(AudioQuality.rank(aac) < AudioQuality.rank(AudioQuality.select(source)!!))
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$title.m4a")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/BiliMusic/")
        })!!
        var file: File? = null
        try {
            val request = Request.Builder().url(AudioQuality.urls(aac).first()).header("Referer", "https://www.bilibili.com/").build()
            AudioDownloadWorker.sharedClient.newCall(request).execute().use { response ->
                assertTrue(response.isSuccessful)
                context.contentResolver.openOutputStream(uri)!!.use { out -> response.body!!.byteStream().use { it.copyTo(out) } }
            }
            val path = context.contentResolver.query(uri, arrayOf("_data"), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
            file = File(path)
            val oldSize = file.length()
            db.songDao().insertSong(Song(id, title, "音质升级验证", duration = 1, coverUrl = "", cid = 773130617,
                bvid = "BV1fB4y1h76Z", aid = 0, uploaderId = 0, uploaderName = "", pubDate = 0,
                localPath = path, fileSize = oldSize, isDownloaded = true))
            QualityUpgradeManager(context).prepareUris()
            prefs.edit().putString("status", "running").commit()
            suspend fun runWorker() {
                val job = OneTimeWorkRequestBuilder<QualityUpgradeWorker>().setInputData(workDataOf("song_id" to id)).build()
                work.enqueue(job).result.get(10, TimeUnit.SECONDS)
                val deadline = System.currentTimeMillis() + 180_000
                var state: WorkInfo.State? = null
                while (System.currentTimeMillis() < deadline) {
                    state = work.getWorkInfoById(job.id).get(10, TimeUnit.SECONDS)?.state
                    if (state?.isFinished == true) break
                    delay(500)
                }
                assertEquals(WorkInfo.State.SUCCEEDED, state)
            }
            runWorker()
            assertEquals(prefs.getString("reason:$id", ""), "upgraded", prefs.getString("result:$id", ""))
            assertEquals("Hi-Res 无损 · FLAC", DownloadedAudioInfo(context).label(path, file.length()))
            assertTrue(file.length() > oldSize)
            assertEquals(file.length(), db.songDao().getSongById(id)!!.fileSize)
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            val modified = file.lastModified()
            prefs.edit().remove("result:$id").commit()
            runWorker()
            assertEquals("unchanged", prefs.getString("result:$id", ""))
            assertEquals(modified, file.lastModified())
            val corrupt = File(context.cacheDir, "upgrade_failure_probe").apply { writeText("broken replacement") }
            try {
                AudioFileReplacement(context).replace(id, file, uri, corrupt) { throw IOException("injected commit failure") }
                fail("必须传播替换失败")
            } catch (expected: IOException) { assertEquals("injected commit failure", expected.message) }
            finally { corrupt.delete() }
            assertArrayEquals(hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            // Simulate the durable state left by process death during overwrite.
            file.copyTo(File(context.filesDir, "audio_upgrade_rollback"), overwrite = true)
            context.getSharedPreferences("audio_replacement_journal", Context.MODE_PRIVATE).edit()
                .putString("path", path).putString("uri", uri.toString()).putString("id", id)
                .putLong("size", file.length()).putString("label", "Hi-Res 无损 · FLAC").commit()
            context.contentResolver.openOutputStream(uri, "rwt")!!.use { it.write(byteArrayOf(1, 2, 3)) }
            AudioFileReplacement(context).recover()
            assertArrayEquals(hash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            assertEquals(file.length(), db.songDao().getSongById(id)!!.fileSize)
            assertFalse(File(context.filesDir, "audio_upgrade_rollback").exists())
        } finally {
            context.contentResolver.delete(uri, null, null)
            file?.let { assertFalse(it.exists()) }
            db.songDao().deleteSongById(id)
            val edit = prefs.edit().clear()
            saved.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
            } }
            edit.commit()
        }
    }
}
