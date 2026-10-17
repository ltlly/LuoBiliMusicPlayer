package com.bilimusicplayer.desktop

import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.DownloadStatus
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.download.DownloadService
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.favorite.AudioQuality
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class DesktopDownloadService(
    private val storage: AppStorage,
    private val scope: CoroutineScope,
) : DownloadService {

    private val database = AppDatabase.getDatabase()
    private val downloadDao = database.downloadDao()
    private val songDao = database.songDao()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val jobs = mutableMapOf<String, Job>()

    override fun observeAll(): Flow<List<Download>> = downloadDao.getAllDownloads()
    override fun observe(songId: String): Flow<Download?> = downloadDao.getDownloadBySongIdFlow(songId)
    override fun activeCount(): Flow<Int> = downloadDao.getDownloadCountByStatuses(
        listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.CONVERTING),
    )

    override suspend fun startDownload(song: Song, audioUrl: String) {
        downloadDao.insertDownload(
            Download(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                status = DownloadStatus.QUEUED,
                audioUrl = audioUrl,
            ),
        )
        jobs[song.id]?.cancel()
        jobs[song.id] = scope.launch(Dispatchers.IO) {
            try {
                val url = audioUrl.ifBlank { resolveUrl(song) }
                downloadDao.updateDownloadStatus(song.id, DownloadStatus.DOWNLOADING)
                val dir = File(storage.downloadSettings.value.downloadDir).apply { mkdirs() }
                val file = File(dir, sanitize(song.title) + ".m4a")
                client.newCall(
                    Request.Builder().url(url)
                        .header("Referer", "https://www.bilibili.com")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                    val body = response.body ?: error("下载失败：空响应")
                    val total = body.contentLength().coerceAtLeast(0)
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var readTotal = 0L
                            var count: Int
                            while (input.read(buffer).also { count = it } >= 0) {
                                output.write(buffer, 0, count)
                                readTotal += count
                                val progress = if (total > 0) ((readTotal * 100) / total).toInt() else 0
                                downloadDao.updateDownloadProgress(song.id, progress, readTotal)
                            }
                        }
                    }
                }
                downloadDao.completeDownload(song.id, DownloadStatus.COMPLETED, file.absolutePath)
                songDao.insertSong(
                    song.copy(
                        isDownloaded = true,
                        localPath = file.absolutePath,
                        fileSize = file.length(),
                    ),
                )
            } catch (e: Exception) {
                downloadDao.failDownload(song.id, DownloadStatus.FAILED, e.message ?: "下载失败")
            } finally {
                jobs.remove(song.id)
            }
        }
    }

    override suspend fun cancel(songId: String) {
        jobs.remove(songId)?.cancel()
        downloadDao.updateDownloadStatus(songId, DownloadStatus.CANCELLED)
    }

    override suspend fun pause(songId: String) {
        jobs.remove(songId)?.cancel()
        downloadDao.updateDownloadStatus(songId, DownloadStatus.PAUSED)
    }

    override suspend fun resume(songId: String) {
        val download = downloadDao.getDownloadBySongId(songId) ?: return
        val song = songDao.getSongById(songId) ?: return
        startDownload(song, download.audioUrl)
    }

    override suspend fun delete(songId: String) {
        jobs.remove(songId)?.cancel()
        downloadDao.getDownloadBySongId(songId)?.localPath?.let { File(it).delete() }
        downloadDao.deleteDownloadBySongId(songId)
    }

    override suspend fun clearCompleted() = downloadDao.deleteDownloadsByStatus(DownloadStatus.COMPLETED)

    override suspend fun clearFailed() = downloadDao.deleteDownloadsByStatus(DownloadStatus.FAILED)

    override suspend fun updateConcurrencyLimit(max: Int) = Unit

    private suspend fun resolveUrl(song: Song): String {
        val repository = BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)
        val detail = repository.getVideoDetail(song.bvid)
        val cid = detail.body()?.data?.cid ?: error("无法获取视频信息")
        val playUrl = repository.getPlayUrl(cid, song.bvid)
        val stream = repository.selectBestAudioStream(playUrl.body()?.data?.dash)
            ?: error("当前账号无法获取音轨")
        return AudioQuality.urls(stream).firstOrNull() ?: error("音轨地址为空")
    }

    private fun sanitize(title: String): String = title
        .replace(Regex("[\\\\/:*?\"<>|]"), "／")
        .take(120)
        .ifBlank { "audio_${System.currentTimeMillis()}" }
}
