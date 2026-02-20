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
import com.bilimusicplayer.data.repository.FavoriteContentCacheRepository
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

    val contentCacheRepository = remember {
        FavoriteContentCacheRepository(database)
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

    // Function to load data from API (with optional search)
    suspend fun loadDataFromApi(page: Int = 1, keyword: String? = null, append: Boolean = false) {
        try {
            Log.d("FavoriteContent", "loadDataFromApi: page=$page, keyword=$keyword, append=$append")
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

                // Cache the data (only for non-search requests)
                if (keyword == null) {
                    withContext(Dispatchers.IO) {
                        if (page == 1 && !append) {
                            // Refresh page 1 without wiping later cached pages
                            contentCacheRepository.refreshPage(folderId, newMedias, pageNumber = 1)
                        } else {
                            // Append new page to cache
                            contentCacheRepository.appendToCache(folderId, newMedias, (page - 1) * 20)
                        }
                    }
                }
            } else {
                val errorMsg = "加载失败: code=${response.body()?.code}, ${response.body()?.message ?: "未知错误"}"
                errorMessage = errorMsg
                Log.e("FavoriteContent", errorMsg)
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: "未知错误"
            errorMessage = errorMsg
            Log.e("FavoriteContent", "loadDataFromApi异常", e)
        }
    }

    // Load first page with cache support
    LaunchedEffect(folderId) {
        scope.launch {
            isLoading = true
            errorMessage = null
            currentPage = 1

            // Step 1: Try loading from cache first for instant display
            val cachedData = withContext(Dispatchers.IO) {
                contentCacheRepository.getCachedMediaList(folderId)
            }

            if (cachedData != null && cachedData.isNotEmpty()) {
                Log.d("FavoriteContent", "从缓存加载 ${cachedData.size} 条数据")
                mediaList = cachedData
                // Calculate which page we're on based on cached data count
                currentPage = (cachedData.size + 19) / 20
                // We don't know the real totalCount yet, but we know hasMore is at least true
                // unless the API tells us otherwise
                isLoading = false
            }

            // Step 2: Always load page 1 from API to get real totalCount
            // This refreshes page 1 in cache without wiping later pages
            try {
                val response = repository.getFavoriteResources(
                    mediaId = folderId,
                    pageNumber = 1,
                    pageSize = 20
                )
                if (response.isSuccessful && response.body()?.code == 0) {
                    val data = response.body()?.data
                    val newMedias = data?.medias ?: emptyList()
                    totalCount = data?.info?.mediaCount ?: 0

                    // Update page 1 in cache
                    withContext(Dispatchers.IO) {
                        contentCacheRepository.refreshPage(folderId, newMedias, pageNumber = 1)
                    }

                    if (cachedData != null && cachedData.size > 20) {
                        // We had more than 1 page cached — keep using the full cached list
                        // but replace the first 20 items with fresh data
                        val updatedList = newMedias + cachedData.drop(20)
                        mediaList = updatedList
                        currentPage = (updatedList.size + 19) / 20
                    } else {
                        // Only had 1 page or no cache — just use the fresh data
                        mediaList = newMedias
                        currentPage = 1
                    }
                    hasMore = mediaList.size < totalCount
                    Log.d("FavoriteContent", "API刷新完成: ${mediaList.size}条, totalCount=$totalCount, hasMore=$hasMore")
                } else if (cachedData == null || cachedData.isEmpty()) {
                    errorMessage = "加载失败: ${response.body()?.message ?: "未知错误"}"
                }
            } catch (e: Exception) {
                if (cachedData == null || cachedData.isEmpty()) {
                    errorMessage = e.message ?: "未知错误"
                }
            }
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
                loadDataFromApi(page = 1, keyword = keyword)
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
                loadDataFromApi(page = nextPage, keyword = keyword, append = true)
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
                                // Capture needed data before launching in application scope
                                val selectedMedia = mediaList.filter { selectedMediaIds.contains(it.bvid) }
                                val selectedCount = selectedMedia.size
                                isBatchDownloading = true
                                isSelectionMode = false
                                selectedMediaIds = emptySet()

                                scope.launch {
                                    snackbarHostState.showSnackbar("开始下载选中的 $selectedCount 项（后台进行中）...")
                                }

                                // Launch in application scope so it survives navigation
                                BiliMusicApplication.instance.applicationScope.launch(Dispatchers.IO) {
                                    batchDownloadMedia(selectedMedia, database, repository, downloadManager)
                                    withContext(Dispatchers.Main) {
                                        isBatchDownloading = false
                                    }
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
                            // Capture needed data before launching in application scope
                            val allMedia = mediaList.toList()
                            isBatchDownloading = true

                            scope.launch {
                                snackbarHostState.showSnackbar("开始批量下载（后台进行中）...")
                            }

                            // Launch in application scope so it survives navigation
                            BiliMusicApplication.instance.applicationScope.launch(Dispatchers.IO) {
                                batchDownloadMedia(allMedia, database, repository, downloadManager)
                                withContext(Dispatchers.Main) {
                                    isBatchDownloading = false
                                }
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
                                        } catch (e: Exception) {
                                            Log.e("FavoriteContent", "播放失败", e)
                                            playingBvid = null
                                            isPlaying = false
                                            snackbarHostState.showSnackbar("播放失败: ${e.message ?: "未知错误"}")
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

/**
 * Batch download media with efficient deduplication and rate limiting.
 *
 * Strategy:
 * 1. Batch-query DB to find already-downloaded and already-queued songs (0 API calls)
 * 2. For remaining items, sequentially call API with delay between each to avoid B站 rate limiting
 * 3. DownloadManager internally limits concurrent actual download workers via Semaphore
 */
private suspend fun batchDownloadMedia(
    mediaList: List<FavoriteMedia>,
    database: AppDatabase,
    repository: BiliFavoriteRepository,
    downloadManager: DownloadManager
) {
    val allBvids = mediaList.map { it.bvid }
    var successCount = 0
    var failCount = 0
    var skippedCount = 0

    // Step 1: Batch dedup - query DB once for all IDs instead of one-by-one
    // Room has a limit on IN clause size (~999), so chunk if needed
    val downloadedIds = mutableSetOf<String>()
    val activeDownloadIds = mutableSetOf<String>()

    allBvids.chunked(500).forEach { chunk ->
        downloadedIds.addAll(database.songDao().getDownloadedSongIds(chunk))
        activeDownloadIds.addAll(database.downloadDao().getActiveDownloadSongIds(chunk))
    }

    val skipIds = downloadedIds + activeDownloadIds
    val toDownload = mediaList.filter { it.bvid !in skipIds }
    skippedCount = mediaList.size - toDownload.size

    Log.d("BatchDownload", "总计: ${mediaList.size}, 跳过: $skippedCount (已下载: ${downloadedIds.size}, 队列中: ${activeDownloadIds.size}), 待处理: ${toDownload.size}")

    // Step 2: Check CachedPlaybackUrl for recently resolved audio URLs (avoid API calls)
    val cachedUrlDao = database.cachedPlaybackUrlDao()
    val allToDownloadBvids = toDownload.map { it.bvid }
    val cachedUrls = mutableMapOf<String, com.bilimusicplayer.data.model.CachedPlaybackUrl>()
    allToDownloadBvids.chunked(500).forEach { chunk ->
        cachedUrlDao.getCachedUrls(chunk)
            .filter { it.expiresAt > System.currentTimeMillis() }
            .forEach { cachedUrls[it.bvid] = it }
    }
    Log.d("BatchDownload", "播放URL缓存命中: ${cachedUrls.size}/${toDownload.size}")

    // Get rate limit delay from settings
    val settingsManager = com.bilimusicplayer.service.download.DownloadSettingsManager(
        com.bilimusicplayer.BiliMusicApplication.instance
    )
    val apiDelayMs = settingsManager.getApiDelayMs()
    Log.d("BatchDownload", "API限速间隔: ${apiDelayMs}ms")
    var lastApiCallTime = 0L

    // Step 3: Sequential processing with rate limiting
    for (media in toDownload) {
        try {
            // Check if we have a cached audio URL
            val cached = cachedUrls[media.bvid]
            if (cached != null) {
                // Use cached URL — 0 API calls
                val song = Song(
                    id = media.bvid,
                    title = media.title,
                    artist = media.upper.name,
                    duration = media.duration,
                    coverUrl = fixImageUrl(media.cover),
                    audioUrl = cached.audioUrl,
                    cid = cached.cid,
                    bvid = media.bvid,
                    aid = media.id,
                    uploaderId = media.upper.mid,
                    uploaderName = media.upper.name,
                    pubDate = media.pubtime
                )
                database.songDao().insertSong(song)
                downloadManager.startDownload(song, cached.audioUrl)
                successCount++
                // No delay needed for cache hits
                continue
            }

            // Cache miss — call API with rate limiting
            val now = System.currentTimeMillis()
            val elapsed = now - lastApiCallTime
            if (elapsed < apiDelayMs) {
                kotlinx.coroutines.delay(apiDelayMs - elapsed)
            }
            lastApiCallTime = System.currentTimeMillis()

            val detailResponse = repository.getVideoDetail(media.bvid)
            if (detailResponse.isSuccessful && detailResponse.body()?.code == 0) {
                val cid = detailResponse.body()?.data?.cid
                if (cid != null) {
                    // Small delay between the two API calls
                    kotlinx.coroutines.delay(200)

                    val playUrlResponse = repository.getPlayUrl(cid, media.bvid)
                    if (playUrlResponse.isSuccessful && playUrlResponse.body()?.code == 0) {
                        val audioUrl = repository.selectBestAudioStream(playUrlResponse.body()?.data?.dash?.audio)?.baseUrl
                        if (audioUrl != null) {
                            // Cache the URL for future use
                            try {
                                cachedUrlDao.insertCachedUrl(
                                    com.bilimusicplayer.data.model.CachedPlaybackUrl(
                                        bvid = media.bvid, cid = cid, audioUrl = audioUrl,
                                        title = media.title, artist = media.upper.name,
                                        coverUrl = fixImageUrl(media.cover), duration = media.duration
                                    )
                                )
                            } catch (_: Exception) {}

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
            Log.e("BatchDownload", "下载失败: ${media.title}", e)
            failCount++
        }

        // Rate limiting is handled before API calls above
        // No additional delay needed for cache hits
    }

    Log.d("BatchDownload", "批量下载入队完成：成功=$successCount, 失败=$failCount, 跳过=$skippedCount")
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
 * Load playlist and start playing from the clicked song.
 * Optimized to minimize API calls:
 * 1. Downloaded songs use local file path (0 API calls)
 * 2. CachedPlaybackUrl cache for recently resolved songs (0 API calls, 6h validity)
 * 3. API calls only for uncached non-downloaded songs, rate-limited by user setting
 * 4. Auto-expands to load ALL songs in the folder
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
    val clickedIndex = mediaList.indexOfFirst { it.bvid == clickedMedia.bvid }.coerceAtLeast(0)

    val context = com.bilimusicplayer.BiliMusicApplication.instance
    val database = com.bilimusicplayer.data.local.AppDatabase.getDatabase(context)
    val cachedUrlDao = database.cachedPlaybackUrlDao()
    val songDao = database.songDao()
    val settingsManager = com.bilimusicplayer.service.download.DownloadSettingsManager(context)

    // Step 1: Batch-query all downloaded songs (0 API calls)
    val allBvids = mediaList.map { it.bvid }
    val downloadedSongsMap = mutableMapOf<String, com.bilimusicplayer.data.model.Song>()
    allBvids.chunked(500).forEach { chunk ->
        songDao.getDownloadedSongsByIds(chunk).forEach { song ->
            if (song.localPath != null && java.io.File(song.localPath).exists()) {
                downloadedSongsMap[song.bvid] = song
            }
        }
    }
    Log.d("PlaylistCache", "已下载歌曲: ${downloadedSongsMap.size}/${mediaList.size}")

    // Step 2: Batch-query cached playback URLs (0 API calls)
    val cachedUrlsMap = mutableMapOf<String, com.bilimusicplayer.data.model.CachedPlaybackUrl>()
    allBvids.chunked(500).forEach { chunk ->
        cachedUrlDao.getCachedUrls(chunk)
            .filter { it.expiresAt > System.currentTimeMillis() }
            .forEach { cachedUrlsMap[it.bvid] = it }
    }
    Log.d("PlaylistCache", "URL缓存命中: ${cachedUrlsMap.size}/${mediaList.size}")

    // Step 3: Load the initial batch (clicked song + a few nearby) for immediate playback
    val initialPlaylist = mutableListOf<MediaItem>()
    val initialBatchSize = 3.coerceAtMost(mediaList.size - clickedIndex)

    for (i in clickedIndex until (clickedIndex + initialBatchSize)) {
        val media = mediaList[i]
        try {
            val mediaItem = resolveMediaItem(media, downloadedSongsMap, cachedUrlsMap, biliRepository, cachedUrlDao)
            if (mediaItem != null) {
                initialPlaylist.add(mediaItem)
            }
        } catch (e: Exception) {
            Log.e("PlaylistCache", "初始加载失败: ${media.title}", e)
            continue
        }
    }

    if (initialPlaylist.isEmpty()) {
        throw Exception("无法获取播放链接，请稍后重试（可能是请求过于频繁）")
    }

    Log.d("PlaylistCache", "调用onPlaylistReady, 共${initialPlaylist.size}首")
    onPlaylistReady(initialPlaylist)

    // Step 4: Auto-expand in background — load ALL remaining songs
    val apiDelayMs = settingsManager.getApiDelayMs()
    Log.d("PlaylistCache", "API限速: ${settingsManager.getApiRateLimit()}次/分钟, 间隔=${apiDelayMs}ms")

    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        var loadedCount = 0
        var apiCallCount = 0
        var lastApiCallTime = 0L

        // Helper to load one media item with rate limiting for API calls only
        suspend fun loadWithRateLimit(media: FavoriteMedia): MediaItem? {
            // Downloaded songs → instant, no API
            val downloaded = downloadedSongsMap[media.bvid]
            if (downloaded != null) {
                return buildLocalMediaItem(downloaded)
            }

            // Cached URL → instant, no API
            val cached = cachedUrlsMap[media.bvid]
            if (cached != null) {
                return buildMediaItem(cached.audioUrl, cached.title, cached.artist, fixImageUrl(media.cover))
            }

            // API call needed → apply rate limiting
            val now = System.currentTimeMillis()
            val elapsed = now - lastApiCallTime
            if (elapsed < apiDelayMs) {
                kotlinx.coroutines.delay(apiDelayMs - elapsed)
            }
            lastApiCallTime = System.currentTimeMillis()

            val result = resolveMediaItemFromApi(biliRepository, cachedUrlDao, media)
            if (result != null) apiCallCount += 2 // getVideoDetail + getPlayUrl
            return result
        }

        // Forward: load all songs after the initial batch
        for (i in (clickedIndex + initialBatchSize) until mediaList.size) {
            try {
                val mediaItem = loadWithRateLimit(mediaList[i]) ?: continue
                withContext(Dispatchers.Main) { onSongLoaded(mediaItem) }
                loadedCount++
            } catch (e: Exception) {
                Log.e("PlaylistCache", "加载失败: ${mediaList[i].title}", e)
            }
        }

        // Backward: load songs before the clicked position
        for (i in 0 until clickedIndex) {
            try {
                val mediaItem = loadWithRateLimit(mediaList[i]) ?: continue
                withContext(Dispatchers.Main) { onSongLoaded(mediaItem) }
                loadedCount++
            } catch (e: Exception) {
                Log.e("PlaylistCache", "加载失败: ${mediaList[i].title}", e)
            }
        }

        // Load songs from subsequent pages if the folder has more than what's loaded
        if (mediaList.size < totalCount) {
            var nextPage = currentPage + 1
            var remainingToLoad = totalCount - mediaList.size

            while (remainingToLoad > 0) {
                try {
                    // Rate limit the page fetch too
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastApiCallTime
                    if (elapsed < apiDelayMs) {
                        kotlinx.coroutines.delay(apiDelayMs - elapsed)
                    }
                    lastApiCallTime = System.currentTimeMillis()

                    val pageResponse = biliRepository.getFavoriteResources(
                        mediaId = folderId, pageNumber = nextPage, pageSize = 20
                    )

                    if (pageResponse.isSuccessful && pageResponse.body()?.code == 0) {
                        val nextPageMedias = pageResponse.body()?.data?.medias ?: emptyList()
                        for (nextMedia in nextPageMedias) {
                            try {
                                val mediaItem = loadWithRateLimit(nextMedia) ?: continue
                                withContext(Dispatchers.Main) { onSongLoaded(mediaItem) }
                                loadedCount++
                            } catch (e: Exception) {
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

        Log.d("PlaylistCache", "播放列表加载完成: 共${loadedCount}首, API请求${apiCallCount}次")
    }
}

/**
 * Resolve a media item using the best available source:
 * 1. Local downloaded file (0 API)
 * 2. Cached playback URL (0 API)
 * 3. API call (2 API calls)
 */
private suspend fun resolveMediaItem(
    media: FavoriteMedia,
    downloadedSongsMap: Map<String, com.bilimusicplayer.data.model.Song>,
    cachedUrlsMap: Map<String, com.bilimusicplayer.data.model.CachedPlaybackUrl>,
    biliRepository: BiliFavoriteRepository,
    cachedUrlDao: com.bilimusicplayer.data.local.CachedPlaybackUrlDao
): MediaItem? {
    // Priority 1: Local downloaded file
    val downloaded = downloadedSongsMap[media.bvid]
    if (downloaded != null) {
        Log.d("PlaylistCache", "本地文件: ${media.title}")
        return buildLocalMediaItem(downloaded)
    }

    // Priority 2: Cached URL
    val cached = cachedUrlsMap[media.bvid]
    if (cached != null) {
        Log.d("PlaylistCache", "URL缓存: ${media.title}")
        return buildMediaItem(cached.audioUrl, cached.title, cached.artist, fixImageUrl(media.cover))
    }

    // Priority 3: API call
    return resolveMediaItemFromApi(biliRepository, cachedUrlDao, media)
}

/**
 * Load a MediaItem from API and cache the result.
 */
private suspend fun resolveMediaItemFromApi(
    biliRepository: BiliFavoriteRepository,
    cachedUrlDao: com.bilimusicplayer.data.local.CachedPlaybackUrlDao,
    media: FavoriteMedia
): MediaItem? {
    Log.d("PlaylistCache", "API请求: ${media.title} (${media.bvid})")
    val detailResponse = biliRepository.getVideoDetail(media.bvid)
    if (!detailResponse.isSuccessful || detailResponse.body()?.code != 0) {
        Log.e("PlaylistCache", "getVideoDetail失败: HTTP=${detailResponse.code()}, code=${detailResponse.body()?.code}")
        return null
    }
    val cid = detailResponse.body()?.data?.cid ?: return null

    val playUrlResponse = biliRepository.getPlayUrl(cid, media.bvid)
    if (!playUrlResponse.isSuccessful || playUrlResponse.body()?.code != 0) {
        Log.e("PlaylistCache", "getPlayUrl失败: HTTP=${playUrlResponse.code()}, code=${playUrlResponse.body()?.code}")
        return null
    }
    val audioUrl = biliRepository.selectBestAudioStream(
        playUrlResponse.body()?.data?.dash?.audio
    )?.baseUrl ?: return null

    // Cache the result
    try {
        cachedUrlDao.insertCachedUrl(
            com.bilimusicplayer.data.model.CachedPlaybackUrl(
                bvid = media.bvid, cid = cid, audioUrl = audioUrl,
                title = media.title, artist = media.upper.name,
                coverUrl = fixImageUrl(media.cover), duration = media.duration
            )
        )
    } catch (_: Exception) {}

    return buildMediaItem(audioUrl, media.title, media.upper.name, fixImageUrl(media.cover))
}

/**
 * Build MediaItem from a locally downloaded Song
 */
private fun buildLocalMediaItem(song: com.bilimusicplayer.data.model.Song): MediaItem {
    val uri = android.net.Uri.fromFile(java.io.File(song.localPath!!))
    return MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setArtworkUri(android.net.Uri.parse(song.coverUrl))
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(uri)
                .build()
        )
        .build()
}

private fun buildMediaItem(audioUrl: String, title: String, artist: String, coverUrl: String): MediaItem {
    return MediaItem.Builder()
        .setUri(audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(android.net.Uri.parse(coverUrl))
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(android.net.Uri.parse(audioUrl))
                .build()
        )
        .build()
}
