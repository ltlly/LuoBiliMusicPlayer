package com.bilimusicplayer.service

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.CachedPlaybackUrl
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import kotlinx.coroutines.runBlocking

/**
 * Lazy audio URL resolver for Bilibili streams.
 *
 * Instead of pre-fetching audio URLs for all 800 songs in a queue upfront
 * (wasting bandwidth and hitting rate limits), we use placeholder URIs
 * (scheme "bili", path = bvid) and only resolve the real streaming URL
 * when ExoPlayer actually needs to buffer a particular song.
 *
 * Flow:
 * 1. Queue is filled with MediaItems using Uri "bili://{bvid}"
 * 2. When ExoPlayer opens a DataSource for that item, our resolver intercepts it
 * 3. Resolver checks: local file? → use it; cached URL? → use it; else → 2 API calls
 * 4. Returns the real audio DataSpec to the upstream HTTP DataSource
 *
 * Benefits:
 * - Queue creation is instant (0 API calls)
 * - Only songs that actually play consume bandwidth
 * - Cached URLs are reused within their 6h validity window
 */
object BiliAudioResolver {

    private const val TAG = "BiliAudioResolver"
    const val SCHEME = "bili"

    /**
     * Check if a URI is a lazy-resolve placeholder
     */
    fun isPlaceholder(uri: Uri): Boolean {
        return uri.scheme == SCHEME
    }

    /**
     * Build a placeholder URI for a bvid
     */
    fun buildPlaceholderUri(bvid: String): Uri {
        return Uri.parse("$SCHEME://$bvid")
    }

    /**
     * Build a MediaItem with lazy-resolve placeholder.
     * No audio URL is fetched — resolution happens at playback time.
     */
    fun buildLazyMediaItem(
        bvid: String,
        title: String,
        artist: String,
        coverUrl: String,
        duration: Int = 0
    ): MediaItem {
        val placeholderUri = buildPlaceholderUri(bvid)
        return MediaItem.Builder()
            .setMediaId(bvid)
            .setUri(placeholderUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(if (coverUrl.isNotEmpty()) Uri.parse(coverUrl) else null)
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(placeholderUri)
                    .build()
            )
            .build()
    }

    /**
     * Create a ResolvingDataSource.Factory that intercepts "bili://" URIs
     * and resolves them to real audio stream URLs on demand.
     */
    fun createResolvingDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory
    ): DataSource.Factory {
        return ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme != SCHEME) {
                // Not a placeholder — pass through (local file or already-resolved HTTP URL)
                return@Factory dataSpec
            }

            val bvid = uri.host ?: uri.path?.removePrefix("/") ?: ""
            if (bvid.isEmpty()) {
                throw IllegalStateException("Empty bvid in placeholder URI: $uri")
            }

            Log.d(TAG, "解析音频URL: $bvid")

            // Resolve the real audio URL (blocking — ExoPlayer calls this on its IO thread)
            val resolvedUrl = runBlocking { resolveAudioUrl(context, bvid) }
                ?: throw IllegalStateException("无法获取播放链接: $bvid")

            Log.d(TAG, "解析成功: $bvid → ${resolvedUrl.take(80)}...")

            // Return a new DataSpec pointing to the real URL
            dataSpec.buildUpon()
                .setUri(Uri.parse(resolvedUrl))
                .build()
        }
    }

    /**
     * Resolve the actual audio streaming URL for a bvid.
     * Priority: 1) Local downloaded file  2) Cached URL  3) Fresh API call
     */
    private suspend fun resolveAudioUrl(context: Context, bvid: String): String? {
        val database = AppDatabase.getDatabase(context)

        // Priority 1: Check if song is downloaded locally
        val song = database.songDao().getSongById(bvid)
        if (song != null && song.isDownloaded && song.localPath != null) {
            val file = java.io.File(song.localPath)
            if (file.exists()) {
                Log.d(TAG, "使用本地文件: $bvid")
                return Uri.fromFile(file).toString()
            }
        }

        // Priority 2: Check cached playback URL
        val cachedUrl = database.cachedPlaybackUrlDao().getCachedUrl(bvid)
        if (cachedUrl != null && cachedUrl.expiresAt > System.currentTimeMillis()) {
            Log.d(TAG, "使用缓存URL: $bvid")
            return cachedUrl.audioUrl
        }

        // Priority 3: Fetch from API (2 calls: getVideoDetail + getPlayUrl)
        Log.d(TAG, "API请求: $bvid")
        val repository = BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)

        val detailResponse = repository.getVideoDetail(bvid)
        if (!detailResponse.isSuccessful || detailResponse.body()?.code != 0) {
            Log.e(TAG, "getVideoDetail失败: ${detailResponse.code()}")
            return null
        }
        val cid = detailResponse.body()?.data?.cid ?: return null

        val playUrlResponse = repository.getPlayUrl(cid, bvid)
        if (!playUrlResponse.isSuccessful || playUrlResponse.body()?.code != 0) {
            Log.e(TAG, "getPlayUrl失败: ${playUrlResponse.code()}")
            return null
        }
        val audioUrl = repository.selectBestAudioStream(
            playUrlResponse.body()?.data?.dash?.audio
        )?.baseUrl ?: return null

        // Cache for next time
        try {
            database.cachedPlaybackUrlDao().insertCachedUrl(
                CachedPlaybackUrl(
                    bvid = bvid,
                    cid = cid,
                    audioUrl = audioUrl,
                    title = song?.title ?: bvid,
                    artist = song?.artist ?: "",
                    coverUrl = song?.coverUrl ?: "",
                    duration = song?.duration ?: 0
                )
            )
        } catch (_: Exception) {}

        return audioUrl
    }
}
