package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TextFieldDefaults
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
import com.bilimusicplayer.data.model.DownloadStatus
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

    val cacheRepository = remember {
        com.bilimusicplayer.data.repository.PlayQueueCacheRepository(database, repository)
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
    var isBatchDownloading by remember { mutableStateOf(false) }

    // Multi-selection state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedMediaIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }

    // Function to load data (with optional search)
    suspend fun loadData(page: Int = 1, keyword: String? = null, append: Boolean = false) {
        try {
            Log.d("FavoriteContent", "loadData: page=$page, keyword=$keyword, append=$append")
            val response = repository.getFavoriteResources(
                mediaId = folderId,
                pageNumber = page,
                pageSize = 20,
                keyword = keyword
            )
            Log.d("FavoriteContent", "API Response: code=${response.body()?.code}, message=${response.body()?.message}")
            if (response.isSuccessful && response.body()?.code == 0) {
                val data = response.body()?.data
                val newMedias = data?.medias ?: emptyList()
                Log.d("FavoriteContent", "获取到 ${newMedias.size} 条数据, totalCount=${data?.info?.mediaCount}")
                mediaList = if (append) {
                    mediaList + newMedias
                } else {
                    newMedias
                }
                totalCount = data?.info?.mediaCount ?: 0
                hasMore = mediaList.size < totalCount
            } else {
                val errorMsg = "加载失败: code=${response.body()?.code}, ${response.body()?.message ?: "未知错误"}"
                errorMessage = errorMsg
                Log.e("FavoriteContent", errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "未知错误"
            errorMessage = errorMsg
            Log.e("FavoriteContent", "loadData异常", e)
        }
    }

    // Load first page
    LaunchedEffect(folderId) {
        scope.launch {
            isLoading = true
            errorMessage = null
            currentPage = 1
            loadData(page = 1)
            isLoading = false
        }
    }

    // Perform search when search query changes
    LaunchedEffect(searchQuery, isSearchActive) {
        if (isSearchActive) {
            // Add debounce for better UX
            kotlinx.coroutines.delay(300)
            scope.launch {
                isSearching = true
                currentPage = 1
                hasMore = true
                val keyword = if (searchQuery.isBlank()) null else searchQuery
                Log.d("FavoriteContent", "执行搜索: keyword=$keyword, folderId=$folderId")
                loadData(page = 1, keyword = keyword)
                isSearching = false
            }
        }
    }

    // Function to load more
    fun loadMore() {
        if (isLoadingMore || !hasMore || isLoading || isSearching) return

        scope.launch {
            isLoadingMore = true
            try {
                val nextPage = currentPage + 1
                val keyword = if (isSearchActive && searchQuery.isNotBlank()) searchQuery else null
                loadData(page = nextPage, keyword = keyword, append = true)
                currentPage = nextPage
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
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索歌曲或艺术家") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    } else if (isSelectionMode) {
                        Text("已选择 ${selectedMediaIds.size} 项")
                    } else {
                        Text(folderTitle)
                    }
                },
                navigationIcon = {
                    if (isSearchActive) {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Default.ArrowBack, "关闭搜索")
                        }
                    } else if (isSelectionMode) {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedMediaIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, "取消选择")
                        }
                    } else {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (isSearchActive) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, "清除搜索")
                            }
                        }
                    } else {
                        // Search button
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Default.Search, "搜索")
                        }
                    if (isSelectionMode) {
                        // Select all button
                        IconButton(onClick = {
                            selectedMediaIds = if (selectedMediaIds.size == mediaList.size) {
                                emptySet()
                            } else {
                                mediaList.map { it.bvid }.toSet()
                            }
                        }) {
                            Icon(
                                if (selectedMediaIds.size == mediaList.size) {
                                    Icons.Default.CheckBox
                                } else {
                                    Icons.Default.CheckBoxOutlineBlank
                                },
                                "全选"
                            )
                        }
                        // Batch download selected button
                        IconButton(
                            onClick = {
                                scope.launch {
                                    isBatchDownloading = true
                                    snackbarHostState.showSnackbar("开始下载选中的 ${selectedMediaIds.size} 项...")
                                    var successCount = 0
                                    var failCount = 0

                                    var skippedCount = 0
                                    for (media in mediaList.filter { selectedMediaIds.contains(it.bvid) }) {
                                        try {
                                            // Check if already downloaded
                                            val existingSong = database.songDao().getSongById(media.bvid)
                                            if (existingSong != null && existingSong.isDownloaded &&
                                                existingSong.localPath != null &&
                                                java.io.File(existingSong.localPath).exists()) {
                                                skippedCount++
                                                continue
                                            }

                                            // Check if already in download queue
                                            val existingDownload = database.downloadDao().getDownloadBySongId(media.bvid)
                                            if (existingDownload != null &&
                                                (existingDownload.status == DownloadStatus.QUEUED ||
                                                 existingDownload.status == DownloadStatus.DOWNLOADING ||
                                                 existingDownload.status == DownloadStatus.CONVERTING)) {
                                                skippedCount++
                                                continue
                                            }

                                            val detailResponse = repository.getVideoDetail(media.bvid)
                                            if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                                                val cid = detailResponse.body()?.data?.cid
                                                if (cid != null) {
                                                    val playUrlResponse = repository.getPlayUrl(cid, media.bvid)
                                                    if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                        val audioUrl = repository.selectBestAudioStream(playUrlResponse.body()?.data?.dash?.audio)?.baseUrl
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
                                    isSelectionMode = false
                                    selectedMediaIds = emptySet()
                                    val message = buildString {
                                        append("下载完成：成功 $successCount 个")
                                        if (failCount > 0) append("，失败 $failCount 个")
                                        if (skippedCount > 0) append("，跳过 $skippedCount 个（已下载）")
                                    }
                                    snackbarHostState.showSnackbar(message)
                                }
                            },
                            enabled = !isBatchDownloading && selectedMediaIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = "下载选中")
                        }
                    } else {
                        // Enter selection mode button
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(Icons.Default.CheckBox, "多选")
                        }
                        // Batch download all button
                        IconButton(
                            onClick = {
                            scope.launch {
                                isBatchDownloading = true
                                snackbarHostState.showSnackbar("开始批量下载...")
                                var successCount = 0
                                var failCount = 0
                                var skippedCount = 0

                                for (media in mediaList) {
                                    try {
                                        // Check if already downloaded
                                        val existingSong = database.songDao().getSongById(media.bvid)
                                        if (existingSong != null && existingSong.isDownloaded &&
                                            existingSong.localPath != null &&
                                            java.io.File(existingSong.localPath).exists()) {
                                            skippedCount++
                                            continue
                                        }

                                        // Check if already in download queue
                                        val existingDownload = database.downloadDao().getDownloadBySongId(media.bvid)
                                        if (existingDownload != null &&
                                            (existingDownload.status == DownloadStatus.QUEUED ||
                                             existingDownload.status == DownloadStatus.DOWNLOADING ||
                                             existingDownload.status == DownloadStatus.CONVERTING)) {
                                            skippedCount++
                                            continue
                                        }

                                        val detailResponse = repository.getVideoDetail(media.bvid)
                                        if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                                            val cid = detailResponse.body()?.data?.cid
                                            if (cid != null) {
                                                val playUrlResponse = repository.getPlayUrl(cid, media.bvid)
                                                if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                    val audioUrl = repository.selectBestAudioStream(playUrlResponse.body()?.data?.dash?.audio)?.baseUrl
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
                                val message = buildString {
                                    append("批量下载完成：成功 $successCount 个")
                                    if (failCount > 0) append("，失败 $failCount 个")
                                    if (skippedCount > 0) append("，跳过 $skippedCount 个（已下载）")
                                }
                                snackbarHostState.showSnackbar(message)
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

                mediaList.isEmpty() && searchQuery.isNotEmpty() -> {
                    Text(
                        text = "未找到匹配的歌曲",
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
                                text = if (searchQuery.isNotEmpty()) {
                                    "找到 ${mediaList.size} 个结果"
                                } else {
                                    "共 $totalCount 个视频"
                                },
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
                                isSelectionMode = isSelectionMode,
                                isSelected = selectedMediaIds.contains(media.bvid),
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedMediaIds = if (selectedMediaIds.contains(media.bvid)) {
                                            selectedMediaIds - media.bvid
                                        } else {
                                            selectedMediaIds + media.bvid
                                        }
                                    }
                                },
                                onPlayClick = {
                                    scope.launch {
                                        try {
                                            playingBvid = media.bvid
                                            isPlaying = true

                                            // Use cache-enabled playlist loading
                                            loadPlaylistWithCache(
                                                cacheRepository = cacheRepository,
                                                biliRepository = repository,
                                                folderId = folderId,
                                                folderName = folderTitle,
                                                folderCover = mediaList.firstOrNull()?.cover ?: "",
                                                clickedMedia = media,
                                                mediaList = mediaList,
                                                totalCount = totalCount,
                                                currentPage = currentPage,
                                                onPlaylistReady = { playlist ->
                                                    BiliMusicApplication.musicPlayerController.setMediaItems(playlist, 0)
                                                    navController.navigate("player")
                                                },
                                                onSongLoaded = { mediaItem ->
                                                    BiliMusicApplication.musicPlayerController.addMediaItem(mediaItem)
                                                }
                                            )

                                            // OLD CODE - Replaced with cache mechanism
                                            /*
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
                                                            val playUrlResponse = repository.getPlayUrl(cid, currentMedia.bvid)
                                                            if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                                val audioUrl = repository.selectBestAudioStream(playUrlResponse.body()?.data?.dash?.audio)?.baseUrl
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
                                                                    val playUrlResponse = repository.getPlayUrl(cid, currentMedia.bvid)
                                                                    if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                                        val audioUrl = repository.selectBestAudioStream(playUrlResponse.body()?.data?.dash?.audio)?.baseUrl
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
                                                                                    val playUrlResponse = repository.getPlayUrl(cid, nextMedia.bvid)
                                                                                    if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                                                                                        val audioUrl = repository.selectBestAudioStream(playUrlResponse.body()?.data?.dash?.audio)?.baseUrl
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
                                            */
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("播放失败: ${e.message ?: "未知错误"}")
                                        } finally {
                                            isPlaying = false
                                            playingBvid = null
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
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onPlayClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isSelectionMode, onClick = onClick),
        tonalElevation = 1.dp,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection checkbox
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
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

            // Action buttons (hidden in selection mode)
            if (!isSelectionMode) {
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

/**
 * Load playlist with cache support
 * Strategy:
 * 1. Load from cache if available (fast)
 * 2. Load first 5 songs from network (quick start)
 * 3. Load all remaining songs in background and update cache
 */
suspend fun loadPlaylistWithCache(
    cacheRepository: com.bilimusicplayer.data.repository.PlayQueueCacheRepository,
    biliRepository: BiliFavoriteRepository,
    folderId: Long,
    folderName: String,
    folderCover: String,
    clickedMedia: FavoriteMedia,
    mediaList: List<FavoriteMedia>,
    totalCount: Int,
    currentPage: Int,
    onPlaylistReady: (List<MediaItem>) -> Unit,
    onSongLoaded: (MediaItem) -> Unit
) {
    // Step 1: Get or create playlist for this favorite folder
    val playlist = cacheRepository.getOrCreatePlaylist(
        biliFavoriteId = folderId,
        folderName = folderName,
        folderCover = fixImageUrl(folderCover)
    )

    // Step 2: Check if we have cached songs
    val cachedSongs = cacheRepository.getCachedSongs(playlist.id)
    val clickedIndex = mediaList.indexOfFirst { it.bvid == clickedMedia.bvid }

    if (cachedSongs.isNotEmpty()) {
        Log.d("PlaylistCache", "找到 ${cachedSongs.size} 首缓存歌曲")

        // Convert cached songs to MediaItems
        val cachedMediaItems = cachedSongs.mapNotNull { song ->
            if (song.audioUrl != null) {
                MediaItem.Builder()
                    .setUri(song.audioUrl)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setArtworkUri(android.net.Uri.parse(song.coverUrl))
                            .build()
                    )
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setMediaUri(android.net.Uri.parse(song.audioUrl))
                            .build()
                    )
                    .build()
            } else null
        }

        // Find the clicked song in cache
        val clickedSongIndex = cachedSongs.indexOfFirst { it.bvid == clickedMedia.bvid }
        val startIndex = if (clickedSongIndex >= 0) clickedSongIndex else 0

        if (cachedMediaItems.isNotEmpty()) {
            // Reorder to start from clicked song
            val reorderedItems = if (startIndex > 0) {
                cachedMediaItems.drop(startIndex) + cachedMediaItems.take(startIndex)
            } else {
                cachedMediaItems
            }

            // Start playing immediately with cached songs
            onPlaylistReady(reorderedItems)
            Log.d("PlaylistCache", "使用缓存立即开始播放，从第 $startIndex 首开始")

            // Check if we need to update cache (if total count changed)
            if (cachedSongs.size < totalCount) {
                Log.d("PlaylistCache", "缓存不完整，后台更新中...")
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    updateCacheInBackground(
                        cacheRepository,
                        biliRepository,
                        playlist.id,
                        folderId,
                        mediaList,
                        totalCount,
                        currentPage,
                        cachedSongs,
                        onSongLoaded
                    )
                }
            }
            return
        }
    }

    // Step 3: No cache available, load from network
    Log.d("PlaylistCache", "无缓存，从网络加载...")
    loadPlaylistFromNetwork(
        cacheRepository,
        biliRepository,
        playlist.id,
        folderId,
        clickedMedia,
        mediaList,
        totalCount,
        currentPage,
        clickedIndex,
        onPlaylistReady,
        onSongLoaded
    )
}

/**
 * Load playlist from network and cache it
 */
private suspend fun loadPlaylistFromNetwork(
    cacheRepository: com.bilimusicplayer.data.repository.PlayQueueCacheRepository,
    biliRepository: BiliFavoriteRepository,
    playlistId: Long,
    folderId: Long,
    clickedMedia: FavoriteMedia,
    mediaList: List<FavoriteMedia>,
    totalCount: Int,
    currentPage: Int,
    clickedIndex: Int,
    onPlaylistReady: (List<MediaItem>) -> Unit,
    onSongLoaded: (MediaItem) -> Unit
) {
    // Load first 5 songs quickly
    val initialPlaylist = mutableListOf<MediaItem>()
    val initialSongs = mutableListOf<Song>()
    val initialBatchSize = 5.coerceAtMost(mediaList.size - clickedIndex)

    for (i in clickedIndex until (clickedIndex + initialBatchSize)) {
        val currentMedia = mediaList[i]
        try {
            val detailResponse = biliRepository.getVideoDetail(currentMedia.bvid)
            if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                val cid = detailResponse.body()?.data?.cid
                if (cid != null) {
                    val playUrlResponse = biliRepository.getPlayUrl(cid, currentMedia.bvid)
                    if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                        val audioUrl = biliRepository.selectBestAudioStream(
                            playUrlResponse.body()?.data?.dash?.audio
                        )?.baseUrl

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

                            // Create Song for caching
                            val song = cacheRepository.favoriteMediaToSong(
                                media = currentMedia,
                                cid = cid,
                                audioUrl = audioUrl,
                                coverUrl = fixImageUrl(currentMedia.cover)
                            )
                            initialSongs.add(song)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PlaylistCache", "加载失败: ${currentMedia.title}", e)
            continue
        }
    }

    if (initialPlaylist.isNotEmpty()) {
        // Cache initial songs
        cacheRepository.cacheSongs(playlistId, initialSongs, 0)

        // Start playing
        onPlaylistReady(initialPlaylist)
        Log.d("PlaylistCache", "初始 ${initialPlaylist.size} 首歌曲加载完成并缓存")

        // Load remaining songs in background
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            var position = initialSongs.size

            // Load remaining songs from current page
            for (i in (clickedIndex + initialBatchSize) until mediaList.size) {
                val currentMedia = mediaList[i]
                try {
                    val (mediaItem, _) = loadAndCacheSong(
                        biliRepository,
                        cacheRepository,
                        currentMedia,
                        playlistId,
                        position
                    ) ?: continue

                    withContext(Dispatchers.Main) {
                        onSongLoaded(mediaItem)
                    }
                    position++
                } catch (e: Exception) {
                    Log.e("PlaylistCache", "加载失败: ${currentMedia.title}", e)
                    continue
                }
            }

            // Load songs from subsequent pages
            if (mediaList.size < totalCount) {
                var nextPage = currentPage + 1
                var remainingToLoad = totalCount - mediaList.size

                while (remainingToLoad > 0) {
                    try {
                        val pageResponse = biliRepository.getFavoriteResources(
                            mediaId = folderId,
                            pageNumber = nextPage,
                            pageSize = 20
                        )

                        if (pageResponse.isSuccessful && pageResponse.body()?.code == 0) {
                            val nextPageMedias = pageResponse.body()?.data?.medias ?: emptyList()

                            for (nextMedia in nextPageMedias) {
                                try {
                                    val (mediaItem, _) = loadAndCacheSong(
                                        biliRepository,
                                        cacheRepository,
                                        nextMedia,
                                        playlistId,
                                        position
                                    ) ?: continue

                                    withContext(Dispatchers.Main) {
                                        onSongLoaded(mediaItem)
                                    }
                                    position++
                                } catch (e: Exception) {
                                    Log.e("PlaylistCache", "加载失败: ${nextMedia.title}", e)
                                    continue
                                }
                            }

                            remainingToLoad -= nextPageMedias.size
                            nextPage++
                        } else {
                            break
                        }
                    } catch (e: Exception) {
                        Log.e("PlaylistCache", "加载第 $nextPage 页出错", e)
                        break
                    }
                }
            }

            Log.d("PlaylistCache", "所有歌曲加载并缓存完成，总共 $position 首")
        }
    }
}

/**
 * Update cache in background for incomplete cache
 */
private suspend fun updateCacheInBackground(
    cacheRepository: com.bilimusicplayer.data.repository.PlayQueueCacheRepository,
    biliRepository: BiliFavoriteRepository,
    playlistId: Long,
    folderId: Long,
    mediaList: List<FavoriteMedia>,
    totalCount: Int,
    currentPage: Int,
    cachedSongs: List<Song>,
    onSongLoaded: (MediaItem) -> Unit
) {
    val cachedBvids = cachedSongs.map { it.bvid }.toSet()
    var position = cachedSongs.size

    // Load uncached songs from current page
    for (media in mediaList) {
        if (media.bvid !in cachedBvids) {
            try {
                val (mediaItem, _) = loadAndCacheSong(
                    biliRepository,
                    cacheRepository,
                    media,
                    playlistId,
                    position
                ) ?: continue

                withContext(Dispatchers.Main) {
                    onSongLoaded(mediaItem)
                }
                position++
            } catch (e: Exception) {
                continue
            }
        }
    }

    // Load songs from subsequent pages
    if (mediaList.size < totalCount) {
        var nextPage = currentPage + 1
        var remainingToLoad = totalCount - mediaList.size

        while (remainingToLoad > 0) {
            try {
                val pageResponse = biliRepository.getFavoriteResources(
                    mediaId = folderId,
                    pageNumber = nextPage,
                    pageSize = 20
                )

                if (pageResponse.isSuccessful && pageResponse.body()?.code == 0) {
                    val nextPageMedias = pageResponse.body()?.data?.medias ?: emptyList()

                    for (nextMedia in nextPageMedias) {
                        if (nextMedia.bvid !in cachedBvids) {
                            try {
                                val (mediaItem, _) = loadAndCacheSong(
                                    biliRepository,
                                    cacheRepository,
                                    nextMedia,
                                    playlistId,
                                    position
                                ) ?: continue

                                withContext(Dispatchers.Main) {
                                    onSongLoaded(mediaItem)
                                }
                                position++
                            } catch (e: Exception) {
                                continue
                            }
                        }
                    }

                    remainingToLoad -= nextPageMedias.size
                    nextPage++
                } else {
                    break
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    Log.d("PlaylistCache", "缓存更新完成")
}

/**
 * Load a single song and cache it
 */
private suspend fun loadAndCacheSong(
    biliRepository: BiliFavoriteRepository,
    cacheRepository: com.bilimusicplayer.data.repository.PlayQueueCacheRepository,
    media: FavoriteMedia,
    playlistId: Long,
    position: Int
): Pair<MediaItem, Song>? {
    val detailResponse = biliRepository.getVideoDetail(media.bvid)
    if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
        val cid = detailResponse.body()?.data?.cid
        if (cid != null) {
            val playUrlResponse = biliRepository.getPlayUrl(cid, media.bvid)
            if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                val audioUrl = biliRepository.selectBestAudioStream(
                    playUrlResponse.body()?.data?.dash?.audio
                )?.baseUrl

                if (audioUrl != null) {
                    val mediaItem = MediaItem.Builder()
                        .setUri(audioUrl)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(media.title)
                                .setArtist(media.upper.name)
                                .setArtworkUri(android.net.Uri.parse(fixImageUrl(media.cover)))
                                .build()
                        )
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setMediaUri(android.net.Uri.parse(audioUrl))
                                .build()
                        )
                        .build()

                    val song = cacheRepository.favoriteMediaToSong(
                        media = media,
                        cid = cid,
                        audioUrl = audioUrl,
                        coverUrl = fixImageUrl(media.cover)
                    )

                    // Cache the song
                    cacheRepository.cacheSong(playlistId, song, position)

                    return Pair(mediaItem, song)
                }
            }
        }
    }
    return null
}
