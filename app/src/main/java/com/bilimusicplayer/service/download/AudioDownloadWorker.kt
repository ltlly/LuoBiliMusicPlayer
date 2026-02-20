package com.bilimusicplayer.service.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.*
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * WorkManager worker for downloading audio files
 */
class AudioDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val database = AppDatabase.getDatabase(context)
    private val downloadDao = database.downloadDao()
    private val songDao = database.songDao()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(KEY_SONG_ID) ?: return@withContext Result.failure()
        val audioUrl = inputData.getString(KEY_AUDIO_URL) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown"
        val artist = inputData.getString(KEY_ARTIST) ?: "Unknown"

        try {
            // Update download status to downloading
            downloadDao.updateDownloadStatus(songId, DownloadStatus.DOWNLOADING)

            // Download audio file
            val audioFile = downloadAudioFile(songId, audioUrl)

            if (audioFile == null) {
                downloadDao.failDownload(
                    songId,
                    DownloadStatus.FAILED,
                    "Failed to download audio"
                )
                return@withContext Result.failure()
            }

            // Update status to converting
            downloadDao.updateDownloadStatus(songId, DownloadStatus.CONVERTING)

            // Convert to MP3 with FFmpeg
            val mp3File = convertToMp3(audioFile, title, artist)

            if (mp3File == null) {
                audioFile.delete()
                downloadDao.failDownload(
                    songId,
                    DownloadStatus.FAILED,
                    "Failed to convert audio"
                )
                return@withContext Result.failure()
            }

            // Delete original file
            audioFile.delete()

            // Update download status to completed
            downloadDao.completeDownload(
                songId,
                DownloadStatus.COMPLETED,
                mp3File.absolutePath
            )

            // Update song in database
            songDao.updateDownloadStatus(
                songId,
                isDownloaded = true,
                localPath = mp3File.absolutePath,
                fileSize = mp3File.length()
            )

            Result.success()
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
     * Download audio file from URL
     */
    private suspend fun downloadAudioFile(songId: String, url: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Referer", "https://www.bilibili.com")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed with code: ${response.code}")
                    return@withContext null
                }

                val totalBytes = response.body?.contentLength() ?: 0
                val inputStream = response.body?.byteStream() ?: return@withContext null

                // Create temp file
                val tempFile = File(applicationContext.cacheDir, "$songId.temp")
                val outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastReportedProgress = -1
                var lastProgressTime = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // Throttle progress updates: only report every 2% change AND at least 500ms apart
                    if (totalBytes > 0) {
                        val progress = (totalBytesRead * 100 / totalBytes).toInt()
                        val now = System.currentTimeMillis()
                        if ((progress - lastReportedProgress >= 2 || progress >= 100) &&
                            (now - lastProgressTime >= 500 || progress >= 100)) {
                            downloadDao.updateDownloadProgress(songId, progress, totalBytesRead)
                            setProgress(workDataOf(KEY_PROGRESS to progress))
                            lastReportedProgress = progress
                            lastProgressTime = now
                        }
                    }
                }

                outputStream.close()
                inputStream.close()

                tempFile
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading file", e)
                null
            }
        }
    }

    /**
     * Convert audio file to MP3 using FFmpeg
     * Note: This is a placeholder - actual FFmpeg integration would be needed
     */
    private suspend fun convertToMp3(
        inputFile: File,
        title: String,
        artist: String
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                // Get public Music directory
                val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

                // Create BiliMusic subdirectory
                val biliMusicDir = File(musicDir, "BiliMusic")
                if (!biliMusicDir.exists()) {
                    biliMusicDir.mkdirs()
                }

                // Create output file with sanitized filename
                // Only remove characters that are invalid in file systems: / \ : * ? " < > | and control chars
                val sanitizedTitle = title
                    .replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "")
                    .trim()
                    .ifEmpty { "unknown" }
                // Use .m4a extension since Bilibili downloads are M4A format
                val outputFile = File(biliMusicDir, "$sanitizedTitle.m4a")

                // TODO: Implement actual FFmpeg conversion to MP3
                // For now, save as M4A which ExoPlayer supports natively
                // Bilibili audio is already in M4A/AAC format
                inputFile.copyTo(outputFile, overwrite = true)

                Log.d(TAG, "保存为M4A格式: ${outputFile.name}")

                // TODO: Add ID3 tags using a library like JAudioTagger
                // This would embed title, artist, album, and cover art

                Log.d(TAG, "File saved to: ${outputFile.absolutePath}")
                outputFile
            } catch (e: Exception) {
                Log.e(TAG, "Error converting to MP3", e)
                null
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
    }
}
