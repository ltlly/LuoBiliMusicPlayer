package com.bilimusicplayer.service.download

import android.content.Context
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

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // Update progress
                    if (totalBytes > 0) {
                        val progress = (totalBytesRead * 100 / totalBytes).toInt()
                        downloadDao.updateDownloadProgress(songId, progress, totalBytesRead)
                        setProgress(workDataOf(KEY_PROGRESS to progress))
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
                // Create output file
                val outputFile = File(
                    applicationContext.getExternalFilesDir(null),
                    "${inputFile.nameWithoutExtension}.mp3"
                )

                // TODO: Implement actual FFmpeg conversion
                // For now, just copy the file as a placeholder
                // In a real implementation, you would use:
                // FFmpeg.execute("-i ${inputFile.absolutePath} -c:a libmp3lame -b:a 192k ${outputFile.absolutePath}")

                inputFile.copyTo(outputFile, overwrite = true)

                // TODO: Add ID3 tags using a library like JAudioTagger
                // This would embed title, artist, album, and cover art

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
