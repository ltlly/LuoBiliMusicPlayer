package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.bilimusicplayer.BiliMusicApplication
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.DownloadStatus
import com.bilimusicplayer.data.model.Song
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }

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

    // Files we asked the user to delete via the system MediaStore prompt — kept
    // around so we can clean up DB rows + try the direct-delete fallback once
    // the user dismisses the dialog.
    var pendingDeletionPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    val deleteRequestLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scope.launch {
            isCleaningUnsynced = true
            val paths = pendingDeletionPaths
            pendingDeletionPaths = emptyList()
            val granted = result.resultCode == android.app.Activity.RESULT_OK
            // Files the system already removed are gone now; for anything left
            // (user denied, or files weren't in MediaStore) try a direct delete.
            val stillThere = paths.filter { java.io.File(it).exists() }
            val fallbackDeleted = withContext(Dispatchers.IO) { deletePathsDirectly(stillThere) }
            val totalRemoved = (paths.size - stillThere.size) + fallbackDeleted
            // Drop DB rows regardless — keeping rows whose files still exist would
            // re-import them on the next scan.
            val rowsRemoved = deleteUnsyncedDbRows(database)
            isCleaningUnsynced = false
            snackbarHostState.showSnackbar(
                if (granted) "已删除 $rowsRemoved 条记录, $totalRemoved 个文件"
                else "用户取消; 已删除 $rowsRemoved 条记录, $totalRemoved 个文件 (其余文件归属其它应用)"
            )
        }
    }

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
                            // Pull the paths we want to delete.
                            val paths = withContext(Dispatchers.IO) {
                                database.songDao().getUnsyncedLocalSongs()
                                    .mapNotNull { it.localPath }
                            }
                            if (paths.isEmpty()) {
                                isCleaningUnsynced = false
                                snackbarHostState.showSnackbar("没有可删除的文件")
                                return@launch
                            }
                            val (uris, missing) = withContext(Dispatchers.IO) {
                                resolveMediaStoreUris(context, paths)
                            }
                            if (uris.isNotEmpty() &&
                                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                            ) {
                                // Android 11+: one batched system prompt. Keep `paths` so we
                                // can also handle the `missing` (un-indexed) ones afterwards.
                                pendingDeletionPaths = paths
                                val pendingIntent = android.provider.MediaStore.createDeleteRequest(
                                    context.contentResolver, uris
                                )
                                val request = androidx.activity.result.IntentSenderRequest.Builder(
                                    pendingIntent.intentSender
                                ).build()
                                deleteRequestLauncher.launch(request)
                                // launcher callback finishes cleanup; release the spinner there
                            } else {
                                // Pre-R or no MediaStore entries: just try direct delete.
                                val deleted = withContext(Dispatchers.IO) { deletePathsDirectly(paths) }
                                val rows = deleteUnsyncedDbRows(database)
                                isCleaningUnsynced = false
                                snackbarHostState.showSnackbar("已删除 $rows 条记录, $deleted 个文件")
                            }
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
                                selectedSongs.forEach { songId ->
                                    localSongs.find { it.id == songId }?.let { song ->
                                        song.localPath?.let { path ->
                                            java.io.File(path).delete()
                                        }
                                        database.songDao().deleteSong(song)
                                    }
                                }
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

    // Auto-scan local Music/BiliMusic directory on first entering the Local tab.
    // Recovers downloaded files that lost their DB entries (e.g. after a
    // destructive Room migration or app reinstall).
    LaunchedEffect(selectedTab) {
        if (selectedTab == 0) {
            scanAndImportOrphanFiles(database)
        }
    }

    // Load data when tab changes
    LaunchedEffect(selectedTab, downloadSubTab) {
        isLoading = true
        when (selectedTab) {
                0 -> {
                    // Show all downloaded songs whose local file still exists on disk
                    database.songDao().getDownloadedSongs().collect { songs ->
                        localSongs = songs.filter { song ->
                            song.localPath?.let { java.io.File(it).exists() } == true
                        }
                        isLoading = false
                    }
                }
                1 -> {
                    // Filter downloads based on sub-tab
                    // Use distinctUntilChanged on status list to avoid recomposition on every progress update
                    database.downloadDao().getAllDownloads()
                        .map { downloadList ->
                            when (downloadSubTab) {
                                0 -> downloadList.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.CONVERTING }
                                1 -> downloadList.filter { it.status == DownloadStatus.QUEUED }
                                2 -> downloadList.filter { it.status == DownloadStatus.COMPLETED }
                                else -> downloadList
                            }
                        }
                        .distinctUntilChanged { old, new ->
                            // Only skip update if the list of IDs+statuses+progress are identical
                            // This prevents excessive recomposition from rapid DB progress writes
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
                            // Delete file
                            song.localPath?.let { path ->
                                java.io.File(path).delete()
                            }
                            // Delete from database
                            database.songDao().deleteSong(song)
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
                            Icon(Icons.Default.ArrowBack, "关闭搜索")
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
                        // Rescan local files (only on Local tab)
                        if (selectedTab == 0) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        isScanning = true
                                        val imported = scanAndImportOrphanFiles(database)
                                        isScanning = false
                                        snackbarHostState.showSnackbar(
                                            if (imported > 0) "已恢复 $imported 首本地歌曲"
                                            else "未找到新的本地文件"
                                        )
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
                                        val n = countUnsyncedLocalSongs(database)
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
                        text = { Text("下载中") }
                    )
                    Tab(
                        selected = downloadSubTab == 1,
                        onClick = { downloadSubTab = 1 },
                        text = { Text("待下载") }
                    )
                    Tab(
                        selected = downloadSubTab == 2,
                        onClick = { downloadSubTab = 2 },
                        text = { Text("已完成") }
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
                                items(filteredSongs) { song ->
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
                                                scope.launch {
                                                // Create MediaItems for all songs in the library
                                                val allMediaItems = filteredSongs.map { s ->
                                                    val uri = if (s.localPath != null && java.io.File(s.localPath).exists()) {
                                                        android.net.Uri.fromFile(java.io.File(s.localPath))
                                                    } else {
                                                        android.net.Uri.parse(s.audioUrl ?: "")
                                                    }

                                                    MediaItem.Builder()
                                                        .setUri(uri)
                                                        .setMediaMetadata(
                                                            MediaMetadata.Builder()
                                                                .setTitle(s.title)
                                                                .setArtist(s.artist)
                                                                .setArtworkUri(android.net.Uri.parse(s.coverUrl))
                                                                .build()
                                                        )
                                                        .setRequestMetadata(
                                                            MediaItem.RequestMetadata.Builder()
                                                                .setMediaUri(uri)
                                                                .build()
                                                        )
                                                        .build()
                                                }

                                                // Find the index of the clicked song
                                                val startIndex = filteredSongs.indexOf(song)
                                                android.util.Log.d("LibraryScreen", "播放列表: ${allMediaItems.size}首歌, 从第${startIndex + 1}首开始")

                                                    // Set all songs as playlist, start from clicked song
                                                    BiliMusicApplication.musicPlayerController.setMediaItems(allMediaItems, startIndex)
                                                    navController.navigate("player")
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
                                items(filteredDownloads) { download ->
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
                                        onDelete = {
                                            showDeleteRecordDialog = download
                                        },
                                        showDelete = downloadSubTab == 2 // Only show delete in completed tab
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 4.dp else 1.dp
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

            // Cover with rounded corners
            Card(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = "封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

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
            defaultElevation = if (isSelected) 4.dp else 1.dp
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
                            DownloadStatus.CONVERTING -> "转换中..."
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

/** Number of placeholder local songs that haven't been matched to B站 favorites yet. */
private suspend fun countUnsyncedLocalSongs(database: AppDatabase): Int =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        database.songDao().countUnsyncedLocalSongs()
    }

/**
 * Resolve the MediaStore content URIs for a list of file paths under
 * Music/BiliMusic. Files in shared storage created by a previous install
 * (different uid) cannot be deleted via direct java.io.File.delete() on
 * Android 11+ — they have to go through a MediaStore delete request that the
 * user explicitly approves once for the whole batch.
 *
 * Returns (uris, pathsNotInMediaStore).
 */
private fun resolveMediaStoreUris(
    context: android.content.Context,
    paths: List<String>
): Pair<List<android.net.Uri>, List<String>> {
    if (paths.isEmpty()) return emptyList<android.net.Uri>() to emptyList()
    val uris = mutableListOf<android.net.Uri>()
    val missing = mutableListOf<String>()
    val collection = android.provider.MediaStore.Audio.Media.getContentUri(
        android.provider.MediaStore.VOLUME_EXTERNAL
    )
    val projection = arrayOf(
        android.provider.MediaStore.Audio.Media._ID,
        android.provider.MediaStore.Audio.Media.DATA
    )
    // Chunk to keep the SQL placeholder list sane.
    paths.chunked(400).forEach { chunk ->
        val placeholders = chunk.joinToString(",") { "?" }
        val sel = "${android.provider.MediaStore.Audio.Media.DATA} IN ($placeholders)"
        context.contentResolver.query(
            collection, projection, sel, chunk.toTypedArray(), null
        )?.use { c ->
            val foundPaths = mutableSetOf<String>()
            val idIdx = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
            val dataIdx = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                foundPaths += c.getString(dataIdx)
                uris += android.content.ContentUris.withAppendedId(collection, id)
            }
            chunk.filterNot { it in foundPaths }.forEach { missing += it }
        } ?: chunk.forEach { missing += it }
    }
    return uris to missing
}

/**
 * Best-effort fallback: try direct File.delete for any path the MediaStore
 * approach didn't cover (e.g. files that haven't been indexed yet).
 */
private fun deletePathsDirectly(paths: List<String>): Int {
    var deleted = 0
    for (p in paths) {
        try {
            if (java.io.File(p).delete()) deleted++
        } catch (e: Exception) {
            android.util.Log.w("LibraryScan", "Direct delete failed: $p", e)
        }
    }
    return deleted
}

/**
 * Drop the placeholder rows whose files were just deleted by MediaStore (or
 * directly). Removes both the songs entry and any matching downloads entry.
 */
private suspend fun deleteUnsyncedDbRows(database: AppDatabase): Int =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val songDao = database.songDao()
        val rows = songDao.getUnsyncedLocalSongs()
        val deleted = songDao.deleteAllUnsyncedLocalSongs()
        if (deleted > 0) deleted else rows.size
    }

/**
 * Scan /sdcard/Music/BiliMusic/ for .m4a files that have no matching Song row
 * (typically after a destructive Room migration) and import them.
 *
 * For each orphan file, we try to match it against the cached B站 favorite-folder
 * entries (`cached_favorite_medias`) by sanitized title. A match lets us recover
 * the real bvid, cover URL, uploader, duration, etc. — so the imported entry
 * looks like a normal downloaded song and dedup works on later re-downloads.
 *
 * Files with no cache match fall back to a synthetic `local_*` placeholder.
 *
 * Re-running the scan upgrades existing `local_*` rows in place when new cache
 * data becomes available.
 *
 * Returns number of files newly imported or upgraded.
 */
private suspend fun scanAndImportOrphanFiles(database: AppDatabase): Int =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val musicDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_MUSIC
            )
            val biliDir = java.io.File(musicDir, "BiliMusic")
            if (!biliDir.exists() || !biliDir.isDirectory) return@withContext 0

            val files = biliDir.listFiles { f ->
                f.isFile && f.length() > 0 && f.name.endsWith(".m4a", ignoreCase = true)
            }?.toList() ?: return@withContext 0

            if (files.isEmpty()) return@withContext 0

            val songDao = database.songDao()
            val allSongs = songDao.getAllSongsOnce()
            val songsByPath = allSongs.filter { it.localPath != null }
                .associateBy { it.localPath!! }
            val songsById = allSongs.associateBy { it.id }
            val knownIds = allSongs.map { it.id }.toMutableSet()

            // Build cache index keyed by both filename forms a cache entry could
            // produce: the new full-width sanitization AND the legacy strip-only
            // form used by old releases. Lets us recover even files that were
            // downloaded before the sanitization fix.
            val cachedAll = database.cachedFavoriteMediaDao().getAll()
            val cacheByFilename = mutableMapOf<String, com.bilimusicplayer.data.model.CachedFavoriteMedia>()
            for (c in cachedAll) {
                val newForm = com.bilimusicplayer.service.download.AudioDownloadWorker
                    .sanitizeFilename(c.title)
                val oldForm = com.bilimusicplayer.service.download.AudioDownloadWorker
                    .legacyStripFilename(c.title)
                cacheByFilename.putIfAbsent(newForm, c)
                cacheByFilename.putIfAbsent(oldForm, c)
            }

            var imported = 0
            for (file in files) {
                val absPath = file.absolutePath
                val baseName = file.nameWithoutExtension
                val existing = songsByPath[absPath]
                val matched = cacheByFilename[baseName]

                // Already imported with full B站 metadata AND marked downloaded — done.
                if (existing != null && !existing.id.startsWith("local_") &&
                    existing.isDownloaded && existing.localPath == absPath) continue

                if (matched != null) {
                    val existingByBv = songsById[matched.bvid]
                    val cover = matched.cover.let {
                        when {
                            it.startsWith("//") -> "https:$it"
                            it.startsWith("http://") -> it.replaceFirst("http://", "https://")
                            else -> it
                        }
                    }
                    val newSong = com.bilimusicplayer.data.model.Song(
                        id = matched.bvid,
                        title = matched.title,
                        artist = matched.upperName.ifBlank { "本地音乐" },
                        album = null,
                        duration = matched.duration,
                        coverUrl = cover,
                        localPath = absPath,
                        audioUrl = existingByBv?.audioUrl,
                        cid = existingByBv?.cid ?: 0L,
                        bvid = matched.bvid,
                        aid = matched.id,
                        uploaderId = matched.upperMid,
                        uploaderName = matched.upperName,
                        pubDate = matched.pubtime,
                        addedDate = existingByBv?.addedDate ?: file.lastModified(),
                        isDownloaded = true,
                        fileSize = file.length()
                    )
                    // Drop any stale local_* placeholder for this same file.
                    existing?.takeIf { it.id.startsWith("local_") && it.id != matched.bvid }
                        ?.let { songDao.deleteSongById(it.id) }
                    // REPLACE-on-conflict will upgrade an existing BV row that
                    // was inserted earlier with isDownloaded=false.
                    songDao.insertSong(newSong)
                    knownIds.add(matched.bvid)
                    imported++
                    continue
                }

                // No cache match. Skip if we've already imported a placeholder for it.
                if (existing != null) continue
                val syntheticId = "local_${baseName.hashCode().toUInt().toString(16)}"
                if (syntheticId in knownIds) continue

                songDao.insertSongIfNotExists(
                    com.bilimusicplayer.data.model.Song(
                        id = syntheticId,
                        title = baseName,
                        artist = "本地音乐",
                        album = null,
                        duration = 0,
                        coverUrl = "",
                        localPath = absPath,
                        audioUrl = null,
                        cid = 0L,
                        bvid = syntheticId,
                        aid = 0L,
                        uploaderId = 0L,
                        uploaderName = "",
                        pubDate = file.lastModified(),
                        addedDate = file.lastModified(),
                        isDownloaded = true,
                        fileSize = file.length()
                    )
                )
                knownIds.add(syntheticId)
                imported++
            }
            android.util.Log.d("LibraryScan", "imported/upgraded $imported orphan files")
            imported
        } catch (e: Exception) {
            android.util.Log.e("LibraryScan", "scanAndImportOrphanFiles failed", e)
            0
        }
    }
