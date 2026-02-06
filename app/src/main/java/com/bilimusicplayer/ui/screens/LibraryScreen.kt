package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import coil.compose.AsyncImage
import com.bilimusicplayer.BiliMusicApplication
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.DownloadStatus
import com.bilimusicplayer.data.model.Song
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { AppDatabase.getDatabase(context) }

    var selectedTab by remember { mutableStateOf(0) }
    var localSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var downloads by remember { mutableStateOf<List<Download>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Load data when tab changes
    LaunchedEffect(selectedTab) {
        scope.launch {
            isLoading = true
            when (selectedTab) {
                0 -> {
                    database.songDao().getAllSongs().collect { songs ->
                        localSongs = songs
                        isLoading = false
                    }
                }
                1 -> {
                    database.downloadDao().getAllDownloads().collect { downloadList ->
                        downloads = downloadList
                        isLoading = false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的音乐库") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("本地歌曲 (${localSongs.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("下载队列 (${downloads.size})") }
                )
            }

            // Content
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                selectedTab == 0 && localSongs.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.MusicNote,
                        title = "您的音乐库是空的",
                        subtitle = "从收藏夹下载歌曲来构建您的音乐库"
                    )
                }
                selectedTab == 1 && downloads.isEmpty() -> {
                    EmptyState(
                        icon = Icons.Default.Download,
                        title = "没有下载任务",
                        subtitle = "在收藏夹中点击下载按钮来添加下载任务"
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (selectedTab) {
                            0 -> {
                                items(localSongs) { song ->
                                    SongListItem(
                                        song = song,
                                        onClick = {
                                            scope.launch {
                                                val mediaItem = MediaItem.Builder()
                                                    .setUri(song.localPath ?: song.audioUrl ?: "")
                                                    .setMediaMetadata(
                                                        MediaMetadata.Builder()
                                                            .setTitle(song.title)
                                                            .setArtist(song.artist)
                                                            .setArtworkUri(android.net.Uri.parse(song.coverUrl))
                                                            .build()
                                                    )
                                                    .build()

                                                BiliMusicApplication.musicPlayerController.setMediaItems(listOf(mediaItem), 0)
                                                navController.navigate("player")
                                            }
                                        }
                                    )
                                }
                            }
                            1 -> {
                                items(downloads) { download ->
                                    DownloadListItem(
                                        download = download,
                                        onCancel = {
                                            scope.launch {
                                                database.downloadDao().updateDownloadStatus(
                                                    download.songId,
                                                    DownloadStatus.CANCELLED
                                                )
                                            }
                                        }
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
fun SongListItem(song: Song, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cover
            Card(modifier = Modifier.size(56.dp)) {
                AsyncImage(
                    model = song.coverUrl,
                    contentDescription = "封面",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
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

            // Downloaded icon
            if (song.isDownloaded) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = "已下载",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun DownloadListItem(
    download: Download,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp
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
                        text = download.songId,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Action button
                when (download.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Cancel, contentDescription = "取消")
                        }
                    }
                    else -> {}
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
