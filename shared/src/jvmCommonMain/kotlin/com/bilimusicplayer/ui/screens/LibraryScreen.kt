package com.bilimusicplayer.ui.screens
import com.bilimusicplayer.core.Log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bilimusicplayer.core.LocalAppContainer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.layout.ContentScale
import com.bilimusicplayer.player.Track
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.DownloadStatus
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.ui.components.BiliCoverThumb
import com.bilimusicplayer.ui.components.SongListItemSkeleton
import com.bilimusicplayer.ui.components.DownloadListItemSkeleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {
    val container = LocalAppContainer.current
    val library = container.library
    val player = container.player
    val scope = rememberCoroutineScope()
    var playRequestJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val database = remember { AppDatabase.getDatabase() }

    val biliRepository = remember {
        com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository(
            com.bilimusicplayer.network.RetrofitClient.biliFavoriteApi
        )
    }
    val authRepository = remember {
        com.bilimusicplayer.network.bilibili.auth.BiliAuthRepository(
            api = com.bilimusicplayer.network.RetrofitClient.biliAuthApi,
            cookieJar = com.bilimusicplayer.network.RetrofitClient.getCookieJar(),
            biliApi = com.bilimusicplayer.network.RetrofitClient.biliApi,
            storage = container.storage,
        )
    }

    var selectedTab by remember { mutableStateOf(0) }
    var downloadSubTab by remember { mutableStateOf(0) } // 0=下载中, 1=待下载, 2=已完成
    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var downloads by remember { mutableStateOf<List<Download>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf<Song?>(null) }
    var showDeleteRecordDialog by remember { mutableStateOf<Download?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // Multi-selection state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSongs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDownloads by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Scan state — recovers orphan files in Music/BiliMusic that are missing DB entries
    var isScanning by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // "Clear unsynced" state — wipes local-only placeholder rows + their files
    // so the user can re-download cleanly from B站 favorites.
    var isCleaningUnsynced by remember { mutableStateOf(false) }
    var unsyncedCleanupConfirm by remember { mutableStateOf<Int?>(null) }


    // Clear-unsynced confirmation dialog
    unsyncedCleanupConfirm?.let { count ->
        AlertDialog(
            onDismissRequest = { unsyncedCleanupConfirm = null },
            title = { Text("清理未同步本地音乐") },
            text = {
                Text(
                    "将删除 $count 首未匹配 B站 收藏夹的本地歌曲及其文件。\n\n" +
                    "用途：删除后,可从收藏夹页面重新下载,以获得正确的封面与元数据。\n\n" +
                    "已匹配真实 BV 号的本地歌曲不会被删除。\n\n" +
                    "若文件归属早期版本(不同 uid)，系统会弹一次确认框授权批量删除。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        unsyncedCleanupConfirm = null
                        scope.launch {
                            isCleaningUnsynced = true
                            val result = library.deleteUnsynced()
                            isCleaningUnsynced = false
                            snackbarHostState.showSnackbar("已删除 ${result.deletedRows} 条记录, ${result.deletedFiles} 个文件")
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { unsyncedCleanupConfirm = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val filteredSongs = remember(localSongs, searchQuery) {
        if (searchQuery.isBlank()) {
            localSongs
        } else {
            localSongs.filter { song ->
                song.title.contains(searchQuery, ignoreCase = true) ||
                song.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }
    val filteredDownloads = remember(downloads, searchQuery) {
        if (searchQuery.isBlank()) {
            downloads
        } else {
            downloads.filter { download ->
                download.title.contains(searchQuery, ignoreCase = true) ||
                download.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Batch delete confirmation dialog
    if (showBatchDeleteDialog) {
        val count = if (selectedTab == 0) selectedSongs.size else selectedDownloads.size
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("批量删除") },
            text = { Text("确定要删除选中的 $count 项吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            if (selectedTab == 0) {
                                library.deleteSongs(selectedSongs.toList())
                            } else {
                                selectedDownloads.forEach { songId ->
                                    database.downloadDao().deleteDownloadBySongId(songId)
                                }
                            }
                            isSelectionMode = false
                            selectedSongs = emptySet()
                            selectedDownloads = emptySet()
                            showBatchDeleteDialog = false
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // Reset selection and search when tab changes
    LaunchedEffect(selectedTab, downloadSubTab) {
        isSelectionMode = false
        selectedSongs = emptySet()
        selectedDownloads = emptySet()
        isSearchActive = false
        searchQuery = ""
    }

    var hasStoragePermission by remember { mutableStateOf(library.hasStoragePermission()) }

    fun runScan(forceSync: Boolean = false) {
        scope.launch {
            isScanning = true
            val result = library.scan(forceSync)
            isScanning = false
            hasStoragePermission = library.hasStoragePermission()
            snackbarHostState.showSnackbar(
                when {
                    result.syncedFavorites && result.imported > 0 -> "已同步收藏夹并恢复 ${result.imported} 首本地歌曲"
                    result.imported > 0 -> "已恢复 ${result.imported} 首本地歌曲"
                    result.syncedFavorites -> "已同步收藏夹缓存"
                    else -> "未找到新的本地文件"
                }
            )
        }
    }

    // Auto-scan on entering the local tab. The platform decides whether a
    // storage permission prompt is required (desktop needs none).
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0 && !isScanning) {
            if (library.requiresStoragePermission && !hasStoragePermission) {
                hasStoragePermission = library.requestStoragePermission()
            }
            if (hasStoragePermission || !library.requiresStoragePermission) {
                runScan()
            } else {
                snackbarHostState.showSnackbar("需要存储权限才能扫描本地音乐文件")
            }
        }
    }

    // Load data when tab changes
    LaunchedEffect(selectedTab, downloadSubTab) {
        isLoading = true
        when (selectedTab) {
                0 -> {
                    // Show all downloaded songs whose local file still exists on disk
                    database.songDao().getDownloadedSongs().collect { songs ->
                        localSongs = withContext(Dispatchers.IO) {
                            songs.filter { song -> song.localPath?.let { java.io.File(it).exists() } == true }
                        }
                        isLoading = false
                    }
                }
                1 -> {
                    // Filter downloads based on sub-tab:
                    // 0 = 活动中 (QUEUED + DOWNLOADING + CONVERTING) — all "in progress"
                    // 1 = 已完成 (COMPLETED)
                    // 2 = 失败/取消 (FAILED + CANCELLED + PAUSED) — actionable
                    database.downloadDao().getAllDownloads()
                        .map { downloadList ->
                            when (downloadSubTab) {
                                0 -> downloadList.filter {
                                    it.status == DownloadStatus.QUEUED ||
                                    it.status == DownloadStatus.DOWNLOADING ||
                                    it.status == DownloadStatus.CONVERTING
                                }
                                1 -> downloadList.filter { it.status == DownloadStatus.COMPLETED }
                                2 -> downloadList.filter {
                                    it.status == DownloadStatus.FAILED ||
                                    it.status == DownloadStatus.CANCELLED ||
                                    it.status == DownloadStatus.PAUSED
                                }
                                else -> downloadList
                            }
                        }
                        .distinctUntilChanged { old, new ->
                            // Only skip update if the list of IDs+statuses+progress are identical
                            old.size == new.size && old.zip(new).all { (a, b) ->
                                a.songId == b.songId && a.status == b.status &&
                                a.progress == b.progress
                            }
                        }
                        .collect { filteredList ->
                            downloads = filteredList
                            isLoading = false
                        }
                }
            }
    }

    // Delete song dialog
    showDeleteDialog?.let { song ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除歌曲") },
            text = { Text("确定要删除 \"${song.title}\" 吗？\n\n这将同时删除本地文件和数据库记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            library.deleteSongs(listOf(song.id))
                            showDeleteDialog = null
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Delete download record dialog
    showDeleteRecordDialog?.let { download ->
        AlertDialog(
            onDismissRequest = { showDeleteRecordDialog = null },
            title = { Text("删除下载记录") },
            text = { Text("确定要删除此下载记录吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            database.downloadDao().deleteDownloadBySongId(download.songId)
                            showDeleteRecordDialog = null
                        }
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteRecordDialog = null }) {
                    Text("取消")
                }
            }
        )
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
                        val count = if (selectedTab == 0) selectedSongs.size else selectedDownloads.size
                        Text("已选择 $count 项")
                    } else {
                        Text("我的音乐库")
                    }
                },
                navigationIcon = {
                    if (isSearchActive) {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭搜索")
                        }
                    } else if (isSelectionMode) {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedSongs = emptySet()
                            selectedDownloads = emptySet()
                        }) {
                            Icon(Icons.Default.Close, "取消选择")
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
                            if (selectedTab == 0) {
                                selectedSongs = if (selectedSongs.size == filteredSongs.size) {
                                    emptySet()
                                } else {
                                    filteredSongs.map { it.id }.toSet()
                                }
                            } else {
                                selectedDownloads = if (selectedDownloads.size == filteredDownloads.size) {
                                    emptySet()
                                } else {
                                    filteredDownloads.map { it.songId }.toSet()
                                }
                            }
                        }) {
                            Icon(
                                if ((selectedTab == 0 && selectedSongs.size == filteredSongs.size) ||
                                    (selectedTab == 1 && selectedDownloads.size == filteredDownloads.size)) {
                                    Icons.Default.CheckBox
                                } else {
                                    Icons.Default.CheckBoxOutlineBlank
                                },
                                "全选"
                            )
                        }
                        // Delete button — shows confirmation dialog
                        IconButton(
                            onClick = { showBatchDeleteDialog = true },
                            enabled = (selectedTab == 0 && selectedSongs.isNotEmpty()) ||
                                     (selectedTab == 1 && selectedDownloads.isNotEmpty())
                        ) {
                            Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        // Rescan local files (only on Local tab) — force full sync
                        if (selectedTab == 0) {
                            IconButton(
                                onClick = {
                                    if (library.requiresStoragePermission && !hasStoragePermission) {
                                        scope.launch { hasStoragePermission = library.requestStoragePermission() }
                                    } else {
                                        runScan(forceSync = true)
                                    }
                                },
                                enabled = !isScanning
                            ) {
                                if (isScanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, "扫描本地文件")
                                }
                            }
                            // Clean up "未同步" (local_*) songs so they can be re-downloaded
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        val n = library.countUnsynced()
                                        if (n == 0) {
                                            snackbarHostState.showSnackbar("没有需要清理的未同步歌曲")
                                        } else {
                                            unsyncedCleanupConfirm = n
                                        }
                                    }
                                },
                                enabled = !isCleaningUnsynced && !isScanning
                            ) {
                                if (isCleaningUnsynced) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.DeleteSweep,
                                        contentDescription = "清理未同步本地音乐",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        // Enter selection mode button
                        IconButton(onClick = { isSelectionMode = true }) {
                            Icon(Icons.Default.CheckBox, "多选")
                        }
                    }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Main tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("本地歌曲") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("下载队列") }
                )
            }

            // Download sub-tabs
            if (selectedTab == 1) {
                TabRow(
                    selectedTabIndex = downloadSubTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Tab(
                        selected = downloadSubTab == 0,
                        onClick = { downloadSubTab = 0 },
                        text = { Text("活动中") }
                    )
                    Tab(
                        selected = downloadSubTab == 1,
                        onClick = { downloadSubTab = 1 },
                        text = { Text("已完成") }
                    )
                    Tab(
                        selected = downloadSubTab == 2,
                        onClick = { downloadSubTab = 2 },
                        text = { Text("失败") }
                    )
                }
            }

            // Content
            when {
                isLoading -> {
                    // Show skeleton loaders
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(5) {
                            if (selectedTab == 0) {
                                SongListItemSkeleton()
                            } else {
                                DownloadListItemSkeleton()
                            }
                        }
                    }
                }
                selectedTab == 0 && localSongs.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.MusicNote,
                        title = "您的音乐库是空的",
                        subtitle = "从收藏夹下载歌曲来构建您的音乐库"
                    )
                }
                selectedTab == 0 && filteredSongs.isEmpty() && searchQuery.isNotEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "未找到匹配的歌曲",
                        subtitle = "尝试使用其他关键词搜索"
                    )
                }
                selectedTab == 1 && downloads.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Download,
                        title = "没有下载任务",
                        subtitle = "在收藏夹中点击下载按钮来添加下载任务"
                    )
                }
                selectedTab == 1 && filteredDownloads.isEmpty() && searchQuery.isNotEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Search,
                        title = "未找到匹配的下载任务",
                        subtitle = "尝试使用其他关键词搜索"
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        when (selectedTab) {
                            0 -> {
                                items(filteredSongs, key = { it.id }, contentType = { "song" }) { song ->
                                    SongListItem(
                                        song = song,
                                        isSelectionMode = isSelectionMode,
                                        isSelected = selectedSongs.contains(song.id),
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedSongs = if (selectedSongs.contains(song.id)) {
                                                    selectedSongs - song.id
                                                } else {
                                                    selectedSongs + song.id
                                                }
                                            } else {
                                                playRequestJob?.cancel()
                                                playRequestJob = scope.launch {
                                                val queueSongs = filteredSongs
                                                val tracks = withContext(Dispatchers.IO) {
                                                    queueSongs.map { s ->
                                                        Track(
                                                            id = s.bvid,
                                                            title = s.title,
                                                            artist = s.artist,
                                                            coverUrl = s.coverUrl,
                                                            uri = if (s.localPath != null && java.io.File(s.localPath).exists()) {
                                                                s.localPath
                                                            } else {
                                                                s.audioUrl.orEmpty()
                                                            },
                                                            durationMs = s.duration * 1000L,
                                                        )
                                                    }
                                                }
                                                val startIndex = queueSongs.indexOf(song)
                                                player.setQueue(tracks, startIndex)
                                                }
                                            }
                                        },
                                        onDelete = {
                                            showDeleteDialog = song
                                        }
                                    )
                                }
                            }
                            1 -> {
                                items(filteredDownloads, key = { it.songId }, contentType = { "download" }) { download ->
                                    DownloadListItem(
                                        download = download,
                                        isSelectionMode = isSelectionMode,
                                        isSelected = selectedDownloads.contains(download.songId),
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedDownloads = if (selectedDownloads.contains(download.songId)) {
                                                    selectedDownloads - download.songId
                                                } else {
                                                    selectedDownloads + download.songId
                                                }
                                            }
                                        },
                                        onCancel = {
                                            scope.launch {
                                                database.downloadDao().updateDownloadStatus(
                                                    download.songId,
                                                    DownloadStatus.CANCELLED
                                                )
                                            }
                                        },
                                        onRetry = {
                                            scope.launch {
                                                // Re-enqueue the failed download
                                                val song = com.bilimusicplayer.data.model.Song(
                                                    id = download.songId,
                                                    title = download.title,
                                                    artist = download.artist,
                                                    duration = 0,
                                                    coverUrl = "",
                                                    cid = 0L,
                                                    bvid = download.songId,
                                                    aid = 0L,
                                                    uploaderId = 0L,
                                                    uploaderName = download.artist,
                                                    pubDate = 0L
                                                )
                                                val downloadManager = container.downloads
                                                downloadManager.startDownload(song, download.audioUrl)
                                            }
                                        },
                                        onDelete = {
                                            showDeleteRecordDialog = download
                                        },
                                        showDelete = downloadSubTab == 1 || downloadSubTab == 2 // Show delete in completed and failed tabs
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongListItem(
    song: Song,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val qualityInfo = LocalAppContainer.current.audio
    val audioQuality by remember(song.localPath) { qualityInfo.observeQualityLabel(song.localPath) }.collectAsState(initial = null)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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

            // Cover: 统一缩略图组件（共享缓存 + CDN 小图）
            BiliCoverThumb(
                url = song.coverUrl,
                modifier = Modifier.size(56.dp),
                cornerRadius = 12.dp
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                audioQuality?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Downloaded icon and delete button (hidden in selection mode)
            if (!isSelectionMode) {
                if (song.isDownloaded) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = "已下载",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Delete button
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadListItem(
    download: Download,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    onCancel: () -> Unit,
    onRetry: () -> Unit = {},
    onDelete: () -> Unit = {},
    showDelete: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(enabled = isSelectionMode, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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

                // Status icon
                Icon(
                    imageVector = when (download.status) {
                        DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
                        DownloadStatus.DOWNLOADING -> Icons.Default.Download
                        DownloadStatus.CONVERTING -> Icons.Default.Transform
                        DownloadStatus.FAILED -> Icons.Default.Error
                        DownloadStatus.CANCELLED -> Icons.Default.Cancel
                        DownloadStatus.PAUSED -> Icons.Default.Pause
                        DownloadStatus.QUEUED -> Icons.Default.Schedule
                    },
                    contentDescription = null,
                    tint = when (download.status) {
                        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                        DownloadStatus.FAILED, DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(32.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = download.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (download.status) {
                            DownloadStatus.QUEUED -> "等待中..."
                            DownloadStatus.DOWNLOADING -> "下载中 ${download.progress}%"
                            DownloadStatus.CONVERTING -> "保存原始音轨..."
                            DownloadStatus.COMPLETED -> "已完成"
                            DownloadStatus.FAILED -> "失败: ${download.errorMessage ?: "未知错误"}"
                            DownloadStatus.PAUSED -> "已暂停"
                            DownloadStatus.CANCELLED -> "已取消"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Action buttons (hidden in selection mode)
                if (!isSelectionMode) {
                    when {
                        download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.QUEUED -> {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.Default.Cancel, contentDescription = "取消")
                            }
                        }
                        download.status == DownloadStatus.FAILED || download.status == DownloadStatus.CANCELLED -> {
                            // Retry button
                            IconButton(onClick = onRetry) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "重试",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (showDelete) {
                                IconButton(onClick = onDelete) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除记录",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        showDelete && download.status == DownloadStatus.COMPLETED -> {
                            IconButton(onClick = onDelete) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除记录",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Progress bar
            if (download.status == DownloadStatus.DOWNLOADING && download.progress > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { download.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}