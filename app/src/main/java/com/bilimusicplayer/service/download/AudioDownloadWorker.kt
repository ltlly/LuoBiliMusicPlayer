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
            // Check if file already exists in Music/BiliMusic. Older releases
            // STRIPPED FAT-forbidden chars from titles ("a/b" → "ab"), so the
            // file on disk may not match our current full-width sanitization
            // ("a/b" → "a／b"). Look for both forms.
            val outputFile = getOutputFile(title)
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
