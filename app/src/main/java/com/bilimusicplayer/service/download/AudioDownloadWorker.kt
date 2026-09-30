package com.bilimusicplayer.service.download

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.DownloadStatus
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.favorite.AudioQuality
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for downloading audio files.
 */
class AudioDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val database = AppDatabase.getDatabase(context)
    private val downloadDao = database.downloadDao()
    private val songDao = database.songDao()

    override suspend fun doWork(): Result = AudioDownloadSlots.withSlot(DownloadSettingsManager(applicationContext)) { performDownload() }

    private suspend fun performDownload(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(KEY_SONG_ID) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown"

        try {
            // Check if file already exists in Music/BiliMusic. Older releases
            // STRIPPED FAT-forbidden chars from titles ("a/b" → "ab"), so the
            // file on disk may not match our current full-width sanitization
            // ("a/b" → "a／b"). Look for both forms.
            val knownLocalFile = songDao.getSongById(songId)?.localPath?.let { File(it) }
            val outputFile = knownLocalFile?.takeIf { it.exists() && it.length() > 0 }
                ?: getOutputFile(title)
                .takeIf { it.exists() && it.length() > 0 }
                ?: getLegacyStrippedOutputFile(title)
                    .takeIf { it.exists() && it.length() > 0 }
                ?: getOutputFile(title)  // fallthrough — neither exists, will trigger download
            if (outputFile.exists() && outputFile.length() > 0) {
                Log.d(TAG, "Reusing existing file: ${outputFile.absolutePath}")
                // The same file may already be linked from an earlier orphan-scan
                // import (id like "local_xxxx"). Drop those stale rows so the
                // Library doesn't show the song twice.
                songDao.deleteOrphanRowsForPath(outputFile.absolutePath, songId)
                downloadDao.completeDownload(
                    songId,
                    DownloadStatus.COMPLETED,
                    outputFile.absolutePath
                )
                songDao.updateDownloadStatus(
                    songId,
                    isDownloaded = true,
                    localPath = outputFile.absolutePath,
                    fileSize = outputFile.length()
                )
                return@withContext Result.success()
            }

            // Update download status to downloading
            downloadDao.updateDownloadStatus(songId, DownloadStatus.DOWNLOADING)

            // Always resolve afresh with the API client's persistent login Cookie.
            // CDN URLs are signed by Bilibili; account cookies belong on the API request.
            val repository = BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)
            val song = songDao.getSongById(songId)
                ?: throw IOException("歌曲信息不存在，请重新加入下载队列")
            val knownCid = song.cid.takeIf { it > 0 } ?: database.cachedPlaybackUrlDao().getCachedUrl(song.bvid)?.cid?.takeIf { it > 0 }
            val cid = knownCid ?: run {
                val detail = repository.getVideoDetail(song.bvid)
                if (!detail.isSuccessful || detail.body()?.code != 0) {
                    throw IOException("获取视频信息失败：${detail.body()?.message ?: detail.code()}")
                }
                detail.body()?.data?.cid ?: throw IOException("视频没有可用音轨")
            }
            val resolved = repository.getPlayUrlWithFreshCidFallback(cid, song.bvid, allowRefresh = knownCid != null)
            if (song.cid != resolved.cid) songDao.updateCid(song.id, resolved.cid)
            val response = resolved.response
            if (!response.isSuccessful || response.body()?.code != 0) {
                throw IOException("获取音频失败：${response.body()?.message ?: response.code()}")
            }
            val stream = repository.selectBestAudioStream(response.body()?.data?.dash)
                ?: throw IOException("当前账号无法获取此视频的音轨")
            Log.d(TAG, "下载音质：${AudioQuality.label(stream)}")
            var audioFile: File? = null
            for (url in AudioQuality.urls(stream)) {
                audioFile = downloadAudioFile(songId, url)
                if (audioFile != null) break
            }

            if (audioFile == null) {
                downloadDao.failDownload(
                    songId,
                    DownloadStatus.FAILED,
                    "Failed to download audio"
                )
                return@withContext Result.failure()
            }

            // Finalize the original track without transcoding
            downloadDao.updateDownloadStatus(songId, DownloadStatus.CONVERTING)

            // Save to final location
            val finalFile = saveToMusicDir(audioFile, title)

            if (finalFile == null) {
                audioFile.delete()
                downloadDao.failDownload(
                    songId,
                    DownloadStatus.FAILED,
                    "Failed to save audio file"
                )
                return@withContext Result.failure()
            }

            // Remember the actual selected format only after the file is complete.
            DownloadedAudioInfo(applicationContext).save(finalFile, stream)
            audioFile.delete()

            // Drop any stale orphan-scan row that pointed to this same file path.
            songDao.deleteOrphanRowsForPath(finalFile.absolutePath, songId)

            // Update download status to completed
            downloadDao.completeDownload(
                songId,
                DownloadStatus.COMPLETED,
                finalFile.absolutePath
            )

            // Update song in database
            songDao.updateDownloadStatus(
                songId,
                isDownloaded = true,
                localPath = finalFile.absolutePath,
                fileSize = finalFile.length()
            )

            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.bilimusicplayer.network.BiliRateLimitedException) {
            return@withContext Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $songId", e)
            downloadDao.failDownload(
                songId,
                DownloadStatus.FAILED,
                e.message ?: "Unknown error"
            )
            Result.failure()
        }
    }

    /**
     * Get the output file path in public Music/BiliMusic directory.
     * Sanitizes filename: replaces chars that break filesystems (/, \, :, *, ?, ", <, >, |, NUL)
     * with '_', trims whitespace/dots, and caps length at 180 bytes to stay below FAT32/ext4 limits.
     */
    private fun getOutputFile(title: String): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val biliMusicDir = File(musicDir, "BiliMusic")
        if (!biliMusicDir.exists()) {
            biliMusicDir.mkdirs()
        }
        val safeName = sanitizeFilename(title)
        return File(biliMusicDir, "$safeName.m4a")
    }

    /**
     * Legacy filename used by releases prior to the full-width-substitution fix:
     * the FAT-forbidden chars were just removed. Used as a fallback so a file
     * downloaded by an older version is still detected as already present.
     */
    private fun getLegacyStrippedOutputFile(title: String): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val biliMusicDir = File(musicDir, "BiliMusic")
        val stripped = legacyStripFilename(title)
        return File(biliMusicDir, "$stripped.m4a")
    }

    /**
     * Download audio file from URL to a temp file.
     */
    private suspend fun downloadAudioFile(songId: String, url: String): File? {
        val tempFile = File(applicationContext.cacheDir, "$songId.${id}.temp")
        var complete = false
        try {
            val request = Request.Builder()
                .url(url)
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            sharedClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("音频服务器返回 ${response.code}")
                val body = response.body ?: throw IOException("音频响应为空")
                val totalBytes = body.contentLength()
                var totalBytesRead = 0L
                var lastProgressTime = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            if (isStopped) throw CancellationException("下载已停止")
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                            totalBytesRead += count
                            val now = System.currentTimeMillis()
                            if (totalBytes > 0 && now - lastProgressTime >= 500) {
                                val progress = (totalBytesRead * 100 / totalBytes).toInt().coerceIn(0, 100)
                                downloadDao.updateDownloadProgress(songId, progress, totalBytesRead)
                                setProgress(workDataOf(KEY_PROGRESS to progress))
                                lastProgressTime = now
                            }
                        }
                    }
                }
                if (totalBytesRead == 0L || (totalBytes > 0 && totalBytesRead != totalBytes)) {
                    throw IOException("音频下载不完整，请重试")
                }
                downloadDao.updateDownloadProgress(songId, 100, totalBytesRead)
            }
            complete = true
            return tempFile
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "音频地址下载失败，尝试备用地址：${e.message}")
            return null
        } finally {
            if (!complete) tempFile.delete()
        }
    }

    /** Publish only the complete track. MediaStore owns the pending file on Android 10+. */
    private suspend fun saveToMusicDir(inputFile: File, title: String): File? {
        val outputFile = getOutputFile(title)
        val resolver = applicationContext.contentResolver
        var pendingUri: Uri? = null
        var published = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, outputFile.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/BiliMusic/")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
                ) ?: throw IOException("无法创建音频文件")
                pendingUri = uri
                resolver.openOutputStream(uri, "w")?.use { output ->
                    copyAudio(inputFile, output)
                } ?: throw IOException("无法写入音频文件")
                currentCoroutineContext().ensureActive()
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1) throw IOException("无法完成音频保存")
                // The provider may disambiguate a filename; persist the actual path.
                val savedPath = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: throw IOException("无法读取已保存音频路径")
                val savedFile = File(savedPath)
                if (!savedFile.exists() || savedFile.length() != inputFile.length()) throw IOException("保存的音频不完整")
                published = true
                return savedFile
            } else {
                outputFile.outputStream().use { copyAudio(inputFile, it) }
                published = true
                return outputFile
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "保存音频失败", e)
            return null
        } finally {
            if (!published) {
                pendingUri?.let { runCatching { resolver.delete(it, null, null) } }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) outputFile.delete()
            }
            inputFile.delete()
        }
    }

    private suspend fun copyAudio(inputFile: File, output: java.io.OutputStream) {
        inputFile.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (isStopped) throw CancellationException("下载已停止")
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
            }
        }
    }

    companion object {
        private const val TAG = "AudioDownloadWorker"
        const val KEY_SONG_ID = "song_id"
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_PROGRESS = "progress"

        // Shared OkHttpClient to avoid creating one per download
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Make a title safe to use as a file name on Android external storage (FAT32/exFAT).
         *
         * FAT-family filesystems forbid `/ \ : * ? " < > |` and control chars in filenames.
         * Earlier releases STRIPPED these chars, which produced confusing names like
         * "完？美？友！人！Vsinger" (missing the `/` separator before "Vsinger").
         *
         * We instead substitute each forbidden char with its full-width Unicode equivalent
         * (e.g. `/` → `／`, `|` → `｜`), so the displayed title looks almost identical
         * to the original and remains readable.
         */
        /**
         * Mirror of the OLD release's title→filename function: strips
         * `/ \ : * ? " < > |` and control chars instead of substituting them.
         * Kept so we can recognise files downloaded by older versions.
         */
        fun legacyStripFilename(raw: String): String {
            return raw
                .replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), "")
                .trim()
                .trimEnd('.', ' ')
                .ifBlank { "untitled" }
        }

        fun sanitizeFilename(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                val replacement = when (ch) {
                    '/'  -> '／'    // U+FF0F FULLWIDTH SOLIDUS
                    '\\' -> '＼'    // U+FF3C FULLWIDTH REVERSE SOLIDUS
                    ':'  -> '：'    // U+FF1A FULLWIDTH COLON
                    '*'  -> '＊'    // U+FF0A FULLWIDTH ASTERISK
                    '?'  -> '？'    // U+FF1F FULLWIDTH QUESTION MARK
                    '"'  -> '＂'    // U+FF02 FULLWIDTH QUOTATION MARK
                    '<'  -> '＜'    // U+FF1C FULLWIDTH LESS-THAN
                    '>'  -> '＞'    // U+FF1E FULLWIDTH GREATER-THAN
                    '|'  -> '｜'    // U+FF5C FULLWIDTH VERTICAL LINE
                    else -> if (ch.code < 0x20) ' ' else ch    // strip control chars
                }
                sb.append(replacement)
            }
            val cleaned = sb.toString()
                .trim()
                .trimEnd('.', ' ')
                .ifBlank { "untitled" }
            // Cap UTF-8 byte length to avoid path-too-long errors (FAT32 limit ~255 bytes)
            val maxBytes = 180
            val bytes = cleaned.toByteArray(Charsets.UTF_8)
            return if (bytes.size <= maxBytes) cleaned
            else String(bytes, 0, maxBytes, Charsets.UTF_8).trimEnd('\uFFFD')
        }
    }
}
