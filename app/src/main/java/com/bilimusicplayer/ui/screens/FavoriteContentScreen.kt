package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DefaultHttpDataSource
import com.bilimusicplayer.BiliMusicApplication
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.network.bilibili.favorite.FavoriteMedia
import com.bilimusicplayer.service.download.DownloadManager
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.data.local.AppDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteContentScreen(
    navController: NavController,
    folderId: Long,
    folderTitle: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val repository = remember {
        BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)
    }

    val downloadManager = remember {
        DownloadManager(context)
    }

    val database = remember {
        AppDatabase.getDatabase(context)
    }

    var mediaList by remember { mutableStateOf<List<FavoriteMedia>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentPage by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }
    var totalCount by remember { mutableStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var playingBvid by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var loadingPlaylist by remember { mutableStateOf(false) }
    var isBatchDownloading by remember { mutableStateOf(false) }

    // Load first page
    LaunchedEffect(folderId) {
        scope.launch {
            isLoading = true
            errorMessage = null
            currentPage = 1
            try {
                val response = repository.getFavoriteResources(
                    mediaId = folderId,
                    pageNumber = 1,
                    pageSize = 20
                )
                if (response.isSuccessful && response.body()?.code == 0) {
                    val data = response.body()?.data
                    mediaList = data?.medias ?: emptyList()
                    totalCount = data?.info?.mediaCount ?: 0
                    hasMore = mediaList.size < totalCount
                } else {
                    errorMessage = "加载失败: ${response.body()?.message ?: "未知错误"}"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "未知错误"
            } finally {
                isLoading = false
            }
        }
    }

    // Function to load more
    fun loadMore() {
        if (isLoadingMore || !hasMore || isLoading) return

        scope.launch {
            isLoadingMore = true
            try {
                val nextPage = currentPage + 1
                val response = repository.getFavoriteResources(
                    mediaId = folderId,
                    pageNumber = nextPage,
                    pageSize = 20
                )
                if (response.isSuccessful && response.body()?.code == 0) {
                    val newMedias = response.body()?.data?.medias ?: emptyList()
                    if (newMedias.isNotEmpty()) {
                        mediaList = mediaList + newMedias
                        currentPage = nextPage
                        hasMore = mediaList.size < totalCount
                    } else {
                        hasMore = false
                    }
                }
            } catch (e: Exception) {
                // Ignore load more errors
            } finally {
                isLoadingMore = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderTitle) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Batch download button
                    IconButton(
                        onClick = {
                            scope.launch {
                                isBatchDownloading = true
                                snackbarHostState.showSnackbar("开始批量下载...")
                                var successCount = 0
                                var failCount = 0

                                for (media in mediaList) {
                                    try {
                                        val detailResponse = repository.getVideoDetail(media.bvid)
                                        if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                                            val cid = detailResponse.body()?.data?.cid
                                            if (cid != null) {
                                                val playUrlResponse = repository.getPlayUrl(cid, media.bvid, quality = 64)
                                                if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                    val audioUrl = playUrlResponse.body()?.data?.dash?.audio?.firstOrNull()?.baseUrl
                                                    if (audioUrl != null) {
                                                        val song = Song(
                                                            id = media.bvid,
                                                            title = media.title,
                                                            artist = media.upper.name,
                                                            duration = media.duration,
                                                            coverUrl = fixImageUrl(media.cover),
                                                            audioUrl = audioUrl,
                                                            cid = cid,
                                                            bvid = media.bvid,
                                                            aid = media.id,
                                                            uploaderId = media.upper.mid,
                                                            uploaderName = media.upper.name,
                                                            pubDate = media.pubtime
                                                        )
                                                        database.songDao().insertSong(song)
                                                        downloadManager.startDownload(song, audioUrl)
                                                        successCount++
                                                    } else failCount++
                                                } else failCount++
                                            } else failCount++
                                        } else failCount++
                                    } catch (e: Exception) {
                                        failCount++
                                    }
                                }

                                isBatchDownloading = false
                                snackbarHostState.showSnackbar("批量下载完成：成功 $successCount 个，失败 $failCount 个")
                            }
                        },
                        enabled = !isBatchDownloading && mediaList.isNotEmpty()
                    ) {
                        if (isBatchDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Download, contentDescription = "全部下载")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                try {
                                    val response = repository.getFavoriteResources(
                                        mediaId = folderId,
                                        pageNumber = 1,
                                        pageSize = 20
                                    )
                                    if (response.isSuccessful && response.body()?.code == 0) {
                                        mediaList = response.body()?.data?.medias ?: emptyList()
                                    } else {
                                        errorMessage = "加载失败"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = e.message
                                } finally {
                                    isLoading = false
                                }
                            }
                        }) {
                            Text("重试")
                        }
                    }
                }

                mediaList.isEmpty() -> {
                    Text(
                        text = "收藏夹为空",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> {
                    val listState = rememberLazyListState()

                    // Detect when scrolled to bottom
                    LaunchedEffect(listState) {
                        snapshotFlow {
                            val layoutInfo = listState.layoutInfo
                            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                            lastVisibleItem?.index
                        }
                            .collect { lastVisibleIndex ->
                                // Trigger load more when 5 items from the end
                                if (lastVisibleIndex != null &&
                                    lastVisibleIndex >= mediaList.size - 5 &&
                                    hasMore &&
                                    !isLoadingMore &&
                                    mediaList.isNotEmpty()) {
                                    loadMore()
                                }
                            }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Header showing total count
                        item {
                            Text(
                                text = "共 $totalCount 个视频",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        items(
                            items = mediaList,
                            key = { media -> media.id }
                        ) { media ->
                            MediaItem(
                                media = media,
                                isPlaying = playingBvid == media.bvid,
                                onPlayClick = {
                                    scope.launch {
                                        try {
                                            playingBvid = media.bvid
                                            isPlaying = true

                                            val clickedIndex = mediaList.indexOf(media)

                                            // Phase 1: Load first 5 songs quickly from current page
                                            val initialPlaylist = mutableListOf<MediaItem>()
                                            val initialBatchSize = 5.coerceAtMost(mediaList.size - clickedIndex)

                                            for (i in clickedIndex until (clickedIndex + initialBatchSize)) {
                                                val currentMedia = mediaList[i]
                                                try {
                                                    val detailResponse = repository.getVideoDetail(currentMedia.bvid)
                                                    if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                                                        val cid = detailResponse.body()?.data?.cid
                                                        if (cid != null) {
                                                            val playUrlResponse = repository.getPlayUrl(cid, currentMedia.bvid, quality = 64)
                                                            if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                                val audioUrl = playUrlResponse.body()?.data?.dash?.audio?.firstOrNull()?.baseUrl
                                                                if (audioUrl != null) {
                                                                    val mediaItem = MediaItem.Builder()
                                                                        .setUri(audioUrl)
                                                                        .setMediaMetadata(
                                                                            MediaMetadata.Builder()
                                                                                .setTitle(currentMedia.title)
                                                                                .setArtist(currentMedia.upper.name)
                                                                                .setArtworkUri(android.net.Uri.parse(fixImageUrl(currentMedia.cover)))
                                                                                .build()
                                                                        )
                                                                        .setRequestMetadata(
                                                                            MediaItem.RequestMetadata.Builder()
                                                                                .setMediaUri(android.net.Uri.parse(audioUrl))
                                                                                .build()
                                                                        )
                                                                        .build()
                                                                    initialPlaylist.add(mediaItem)
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    continue
                                                }
                                            }

                                            if (initialPlaylist.isNotEmpty()) {
                                                // Start playing immediately with initial batch
                                                BiliMusicApplication.musicPlayerController.setMediaItems(initialPlaylist, 0)
                                                navController.navigate("player")

                                                Log.d("FavoriteContent", "初始播放列表加载完成，共 ${initialPlaylist.size} 首")
                                                Log.d("FavoriteContent", "收藏夹总数: $totalCount, 当前已加载: ${mediaList.size}")

                                                // Phase 2: Load ALL remaining songs from current page and subsequent pages
                                                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                                    var loadedCount = 0

                                                    // First, load remaining songs from current page
                                                    for (i in (clickedIndex + initialBatchSize) until mediaList.size) {
                                                        val currentMedia = mediaList[i]
                                                        try {
                                                            Log.d("FavoriteContent", "加载当前页第 ${i - clickedIndex + 1} 首: ${currentMedia.title}")
                                                            val detailResponse = repository.getVideoDetail(currentMedia.bvid)
                                                            if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                                                                val cid = detailResponse.body()?.data?.cid
                                                                if (cid != null) {
                                                                    val playUrlResponse = repository.getPlayUrl(cid, currentMedia.bvid, quality = 64)
                                                                    if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                                        val audioUrl = playUrlResponse.body()?.data?.dash?.audio?.firstOrNull()?.baseUrl
                                                                        if (audioUrl != null) {
                                                                            val mediaItem = MediaItem.Builder()
                                                                                .setUri(audioUrl)
                                                                                .setMediaMetadata(
                                                                                    MediaMetadata.Builder()
                                                                                        .setTitle(currentMedia.title)
                                                                                        .setArtist(currentMedia.upper.name)
                                                                                        .setArtworkUri(android.net.Uri.parse(fixImageUrl(currentMedia.cover)))
                                                                                        .build()
                                                                                )
                                                                                .setRequestMetadata(
                                                                                    MediaItem.RequestMetadata.Builder()
                                                                                        .setMediaUri(android.net.Uri.parse(audioUrl))
                                                                                        .build()
                                                                                )
                                                                                .build()
                                                                            withContext(Dispatchers.Main) {
                                                                                BiliMusicApplication.musicPlayerController.addMediaItem(mediaItem)
                                                                            }
                                                                            loadedCount++
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("FavoriteContent", "加载失败: ${currentMedia.title}, ${e.message}")
                                                            continue
                                                        }
                                                    }

                                                    // Then, load songs from subsequent pages
                                                    if (mediaList.size < totalCount) {
                                                        Log.d("FavoriteContent", "开始加载后续页面...")
                                                        var nextPage = currentPage + 1
                                                        var remainingToLoad = totalCount - mediaList.size

                                                        while (remainingToLoad > 0) {
                                                            try {
                                                                Log.d("FavoriteContent", "加载第 $nextPage 页...")
                                                                val pageResponse = repository.getFavoriteResources(
                                                                    mediaId = folderId,
                                                                    pageNumber = nextPage,
                                                                    pageSize = 20
                                                                )

                                                                if (pageResponse.isSuccessful && pageResponse.body()?.code == 0) {
                                                                    val nextPageMedias = pageResponse.body()?.data?.medias ?: emptyList()
                                                                    Log.d("FavoriteContent", "第 $nextPage 页加载成功，共 ${nextPageMedias.size} 首")

                                                                    for (nextMedia in nextPageMedias) {
                                                                        try {
                                                                            val detailResponse = repository.getVideoDetail(nextMedia.bvid)
                                                                            if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                                                                                val cid = detailResponse.body()?.data?.cid
                                                                                if (cid != null) {
                                                                                    val playUrlResponse = repository.getPlayUrl(cid, nextMedia.bvid, quality = 64)
                                                                                    if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                                                        val audioUrl = playUrlResponse.body()?.data?.dash?.audio?.firstOrNull()?.baseUrl
                                                                                        if (audioUrl != null) {
                                                                                            val mediaItem = MediaItem.Builder()
                                                                                                .setUri(audioUrl)
                                                                                                .setMediaMetadata(
                                                                                                    MediaMetadata.Builder()
                                                                                                        .setTitle(nextMedia.title)
                                                                                                        .setArtist(nextMedia.upper.name)
                                                                                                        .setArtworkUri(android.net.Uri.parse(fixImageUrl(nextMedia.cover)))
                                                                                                        .build()
                                                                                                )
                                                                                                .setRequestMetadata(
                                                                                                    MediaItem.RequestMetadata.Builder()
                                                                                                        .setMediaUri(android.net.Uri.parse(audioUrl))
                                                                                                        .build()
                                                                                                )
                                                                                                .build()
                                                                                            withContext(Dispatchers.Main) {
                                                                                                BiliMusicApplication.musicPlayerController.addMediaItem(mediaItem)
                                                                                            }
                                                                                            loadedCount++
                                                                                        }
                                                                                    }
                                                                                }
                                                                            }
                                                                        } catch (e: Exception) {
                                                                            Log.e("FavoriteContent", "加载失败: ${nextMedia.title}, ${e.message}")
                                                                            continue
                                                                        }
                                                                    }

                                                                    remainingToLoad -= nextPageMedias.size
                                                                    nextPage++
                                                                } else {
                                                                    Log.e("FavoriteContent", "加载第 $nextPage 页失败")
                                                                    break
                                                                }
                                                            } catch (e: Exception) {
                                                                Log.e("FavoriteContent", "加载第 $nextPage 页出错: ${e.message}")
                                                                break
                                                            }
                                                        }
                                                    }

                                                    Log.d("FavoriteContent", "所有歌曲加载完成，总共加载了 $loadedCount 首")
                                                }
                                            } else {
                                                snackbarHostState.showSnackbar("无法加载播放列表")
                                            }
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("播放失败: ${e.message ?: "未知错误"}")
                                        } finally {
                                            isPlaying = false
                                            playingBvid = null
                                        }
                                    }
                                },
                                onDownloadClick = {
                                    scope.launch {
                                        try {
                                            // Get video detail and audio URL
                                            val detailResponse = repository.getVideoDetail(media.bvid)
                                            if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                                                val cid = detailResponse.body()?.data?.cid
                                                if (cid != null) {
                                                    val playUrlResponse = repository.getPlayUrl(cid, media.bvid, quality = 64)
                                                    if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                        val audioUrl = playUrlResponse.body()?.data?.dash?.audio?.firstOrNull()?.baseUrl
                                                        if (audioUrl != null) {
                                                            // Create Song entity
                                                            val song = Song(
                                                                id = media.bvid,
                                                                title = media.title,
                                                                artist = media.upper.name,
                                                                duration = media.duration,
                                                                coverUrl = fixImageUrl(media.cover),
                                                                audioUrl = audioUrl,
                                                                cid = cid,
                                                                bvid = media.bvid,
                                                                aid = media.id,
                                                                uploaderId = media.upper.mid,
                                                                uploaderName = media.upper.name,
                                                                pubDate = media.pubtime
                                                            )

                                                            // Save to database
                                                            database.songDao().insertSong(song)

                                                            // Start download
                                                            downloadManager.startDownload(song, audioUrl)

                                                            snackbarHostState.showSnackbar("已添加到下载队列")
                                                        } else {
                                                            snackbarHostState.showSnackbar("无法获取音频URL")
                                                        }
                                                    } else {
                                                        snackbarHostState.showSnackbar("获取播放地址失败")
                                                    }
                                                } else {
                                                    snackbarHostState.showSnackbar("无法获取视频CID")
                                                }
                                            } else {
                                                snackbarHostState.showSnackbar("获取视频详情失败")
                                            }
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("下载失败: ${e.message}")
                                        }
                                    }
                                }
                            )
                        }

                        // Loading more indicator
                        if (isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }

                        // End indicator
                        if (!hasMore && mediaList.isNotEmpty()) {
                            item {
                                Text(
                                    text = "已加载全部内容",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaItem(
    media: FavoriteMedia,
    isPlaying: Boolean = false,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cover image - optimized for smooth scrolling
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(fixImageUrl(media.cover))
                    .crossfade(false) // Disable crossfade for better performance
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(120, 90) // Fixed size in pixels for better performance
                    .scale(Scale.FIT)
                    .build(),
                contentDescription = "封面",
                modifier = Modifier
                    .size(60.dp, 45.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = media.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = media.upper.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDuration(media.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(40.dp),
                    enabled = !isPlaying
                ) {
                    if (isPlaying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "播放",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(
                    onClick = onDownloadClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "下载",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return String.format("%d:%02d", minutes, remainingSeconds)
}

private fun fixImageUrl(url: String): String {
    return when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> url.replace("http://", "https://")
        else -> url
    }
}
