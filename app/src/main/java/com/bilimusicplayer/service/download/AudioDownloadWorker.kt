package com.bilimusicplayer.service.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songId = inputData.getString(KEY_SONG_ID) ?: return@withContext Result.failure()
        val audioUrl = inputData.getString(KEY_AUDIO_URL) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Unknown"
        val artist = inputData.getString(KEY_ARTIST) ?: "Unknown"

        try {
            // Check if file already exists in legacy public Music/BiliMusic directory
            val outputFile = getOutputFile(title)
            if (outputFile.exists() && outputFile.length() > 0) {
                Log.d(TAG, "Reusing existing file: ${outputFile.absolutePath}")
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

            // Delete temp file
            audioFile.delete()

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
     * Uses the original title as-is without any character filtering.
     */
    private fun getOutputFile(title: String): File {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val biliMusicDir = File(musicDir, "BiliMusic")
        if (!biliMusicDir.exists()) {
            biliMusicDir.mkdirs()
        }
        return File(biliMusicDir, "$title.m4a")
    }

    /**
     * Download audio file from URL to a temp file.
     */
    private suspend fun downloadAudioFile(songId: String, url: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Referer", "https://www.bilibili.com")
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val response = sharedClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed with code: ${response.code}")
                    response.close()
                    return@withContext null
                }

                val totalBytes = response.body?.contentLength() ?: 0
                val tempFile = File(applicationContext.cacheDir, "$songId.temp")

                response.body?.byteStream()?.use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0L
                        var lastReportedProgress = -1
                        var lastProgressTime = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            // Check if worker was cancelled
                            if (isStopped) {
                                Log.d(TAG, "Download cancelled for $songId")
                                tempFile.delete()
                                return@withContext null
                            }

                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            // Throttle progress updates
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
                    }
                } ?: run {
                    response.close()
                    return@withContext null
                }

                tempFile
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading file", e)
                null
            }
        }
    }

    /**
     * Save the downloaded temp file to Music/BiliMusic with the original title.
     * No filename sanitization — uses title as-is.
     */
    private suspend fun saveToMusicDir(inputFile: File, title: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val outputFile = getOutputFile(title)
                inputFile.copyTo(outputFile, overwrite = true)
                Log.d(TAG, "File saved to: ${outputFile.absolutePath}")
                outputFile
            } catch (e: Exception) {
                Log.e(TAG, "Error saving file: ${e.message}", e)
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

        // Shared OkHttpClient to avoid creating one per download
        val sharedClient: OkHttpClient by lazy { OkHttpClient() }
    }
}
