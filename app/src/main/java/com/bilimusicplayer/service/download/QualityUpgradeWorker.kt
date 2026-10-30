package com.bilimusicplayer.service.download

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.network.BiliRateLimitedException
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.favorite.AudioQuality
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A durable sequential chain: failure of one source never deletes it or blocks other songs. */
class QualityUpgradeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("song_id") ?: return@withContext Result.success()
        val manager = QualityUpgradeManager(applicationContext)
        if (manager.outcome(id) != null) return@withContext Result.success()
        if (manager.snapshot().status != "running") return@withContext Result.retry()
        val temp = File(applicationContext.cacheDir, "upgrade_${id.hashCode()}_${this@QualityUpgradeWorker.id}.temp")
        try {
            AudioFileReplacement(applicationContext).recover()
            val db = AppDatabase.getDatabase()
            val song = db.songDao().getSongById(id) ?: throw IOException("歌曲记录不存在")
            manager.current(song.title)
            val original = File(song.localPath ?: throw IOException("没有本地音频路径"))
            if (!original.exists() || original.length() == 0L) throw IOException("本地音频文件不存在，已保留歌曲记录")
            if (!song.bvid.startsWith("BV")) throw IOException("缺少B站视频编号，已保留本地音频")
            val uri = manager.uri(original.absolutePath) ?: throw IOException("系统媒体库未收录此文件，已保留原文件")
            if (applicationContext.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED && !original.canWrite()) {
                manager.permissionRequired()
                return@withContext Result.retry()
            }
            val settings = DownloadSettingsManager(applicationContext)
            AudioDownloadSlots.withSlot(settings) {
                val repo = BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)
                val knownCid = song.cid.takeIf { it > 0 } ?: db.cachedPlaybackUrlDao().getCachedUrl(song.bvid)?.cid?.takeIf { it > 0 }
                val cid = knownCid ?: run {
                    val detail = repo.getVideoDetail(song.bvid)
                    if (!detail.isSuccessful || detail.body()?.code != 0) throw IOException("视频信息不可用：${detail.body()?.message ?: detail.code()}")
                    detail.body()?.data?.cid ?: throw IOException("视频没有可用音轨")
                }
                val resolved = repo.getPlayUrlWithFreshCidFallback(cid, song.bvid, allowRefresh = knownCid != null)
                if (song.cid != resolved.cid) db.songDao().updateCid(id, resolved.cid)
                val response = resolved.response
                if (!response.isSuccessful || response.body()?.code != 0) throw IOException("无法获取音轨：${response.body()?.message ?: response.code()}")
                val stream = AudioQuality.select(response.body()?.data?.dash) ?: throw IOException("当前账号没有可用音轨")
                // Losing account access or a changed source must never downgrade existing lossless/Dolby.
                val (detectedRank, existingBitRate) = runCatching { audioInfo(original) }.getOrDefault(0 to 0L)
                val existingRank = maxOf(detectedRank, DownloadedAudioInfo(applicationContext).rank(original))
                val selectedRank = AudioQuality.rank(stream)
                if (existingRank > selectedRank || (existingRank <= 3 && selectedRank <= 3 && stream.bandwidth > 0 && existingBitRate > stream.bandwidth * 1.15)) {
                    manager.record(id, "unchanged", "现有音质高于当前可获取音轨，已保留")
                    return@withSlot
                }
                var downloaded = false
                var same = false
                var lastError: IOException? = null
                for (url in AudioQuality.urls(stream)) {
                    try {
                        same = fetch(url, original, temp)
                        downloaded = true
                        break
                    } catch (e: IOException) { lastError = e }
                }
                if (!downloaded) throw lastError ?: IOException("音频下载失败，已保留原文件")
                if (same) {
                    DownloadedAudioInfo(applicationContext).save(original, stream)
                    manager.record(id, "unchanged")
                } else {
                    audioRank(temp) // Reject malformed/incomplete containers before touching the old file.
                    currentCoroutineContext().ensureActive()
                    val player = com.bilimusicplayer.BiliMusicApplication.instance.container.player
                    val playback = withContext(Dispatchers.Main) { player.suspendLocalFileUpdate(original.absolutePath) }
                    try {
                        AudioFileReplacement(applicationContext).replace(id, original, uri, temp) {
                            db.songDao().updateDownloadStatus(id, true, original.absolutePath, temp.length())
                            DownloadedAudioInfo(applicationContext).save(original, stream)
                        }
                    } finally {
                        withContext(Dispatchers.Main + NonCancellable) { player.resumeLocalFileUpdate(original.absolutePath, playback) }
                    }
                    manager.record(id, "upgraded")
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BiliRateLimitedException) {
            manager.waitingForRateLimit(e.message ?: "B站暂时限制请求")
            Result.retry()
        } catch (e: com.bilimusicplayer.network.bilibili.favorite.WbiInitializationException) {
            // Global signing failures stop the chain at this song, retaining every outcome.
            manager.waitingForSignature(e.message ?: "签名初始化暂不可用")
            Result.retry()
        } catch (e: IOException) {
            // Transient network failures retry only this song, without retrying completed songs.
            if (e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.net.ConnectException) {
                if (runAttemptCount < 3) {
                    manager.waitingForNetwork()
                    return@withContext Result.retry()
                }
            }
            manager.record(id, "failed", e.message ?: "无法升级，已保留原文件")
            Result.success()
        } catch (e: SecurityException) {
            manager.permissionRequired()
            Result.retry()
        } catch (e: Exception) {
            manager.record(id, "failed", e.message ?: "无法升级，已保留原文件")
            Result.success()
        } finally { temp.delete() }
    }

    /** Same signed CDN object, same total length and 64 KiB prefix: avoid transferring it again. */
    private suspend fun fetch(url: String, old: File, temp: File): Boolean {
        val request = Request.Builder().url(url).header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", "Mozilla/5.0").build()
        AudioDownloadWorker.sharedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("音频服务器返回 ${response.code}")
            val body = response.body ?: throw IOException("音频响应为空")
            val length = body.contentLength()
            body.byteStream().use { input ->
                val prefix = ByteArray(64 * 1024)
                var prefixSize = 0
                while (prefixSize < prefix.size) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(prefix, prefixSize, prefix.size - prefixSize)
                    if (count == -1) break
                    prefixSize += count
                }
                if (length > 0 && length == old.length() && prefixSize > 0) {
                    val local = ByteArray(prefixSize)
                    val read = old.inputStream().use { it.read(local) }
                    if (read == prefixSize && local.contentEquals(prefix.copyOf(prefixSize))) return true
                }
                var received = prefixSize.toLong()
                FileOutputStream(temp).use { output ->
                    output.write(prefix, 0, prefixSize)
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        received += count
                    }
                    output.fd.sync()
                }
                if (received == 0L || (length > 0 && received != length)) throw IOException("下载音频不完整，已保留旧文件")
            }
        }
        return false
    }
    private fun audioRank(file: File): Int = audioInfo(file).first

    private fun audioInfo(file: File): Pair<Int, Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                    val bitRate = if (durationUs > 0) file.length() * 8_000_000L / durationUs else 0L
                    val rank = when {
                        mime.contains("flac") -> 5
                        mime.contains("eac3") -> 4
                        bitRate > 192_000 -> 3
                        bitRate > 96_000 -> 2
                        bitRate > 0 -> 1
                        else -> 0
                    }
                    return rank to bitRate
                }
            }
            throw IOException("文件不含可解码音轨，已保留原文件")
        } finally { extractor.release() }
    }
}