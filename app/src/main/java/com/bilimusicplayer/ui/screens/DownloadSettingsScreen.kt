package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import android.os.Environment
import com.bilimusicplayer.service.download.DownloadSettingsManager
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val downloadSettingsManager = remember { DownloadSettingsManager(context) }

    // Calculate cache size
    var cacheSize by remember { mutableStateOf("计算中...") }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // Concurrent downloads setting
    val maxConcurrentDownloads by downloadSettingsManager.maxConcurrentDownloads.collectAsState(
        initial = DownloadSettingsManager.DEFAULT_MAX_CONCURRENT
    )
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    val displayValue = sliderValue?.roundToInt() ?: maxConcurrentDownloads

    LaunchedEffect(Unit) {
        scope.launch {
            val exoplayerCache = File(context.cacheDir, "exoplayer_cache")
            val size = calculateFolderSize(exoplayerCache)
            cacheSize = formatFileSize(size)
        }
    }

    // Clear cache dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("清除缓存") },
            text = { Text("确定要清除所有缓存吗？这将删除已缓存的音频文件。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val exoplayerCache = File(context.cacheDir, "exoplayer_cache")
                            exoplayerCache.deleteRecursively()
                            cacheSize = "0 B"
                            showClearCacheDialog = false
                        }
                    }
                ) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载设置") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Download path
            SettingsSection(title = "存储位置")

            SettingsInfoItem(
                icon = Icons.Default.Folder,
                title = "下载路径",
                subtitle = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)}/BiliMusic"
            )

            HorizontalDivider()

            // Cache settings
            SettingsSection(title = "缓存管理")

            SettingsActionItem(
                icon = Icons.Default.Storage,
                title = "缓存大小",
                subtitle = "已使用: $cacheSize",
                actionText = "清除",
                onActionClick = {
                    showClearCacheDialog = true
                }
            )

            HorizontalDivider()

            // Concurrent downloads setting
            SettingsSection(title = "并发下载")

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "最大并发线程数",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "$displayValue",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "控制同时下载的任务数量，过多可能触发B站风控",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = sliderValue ?: maxConcurrentDownloads.toFloat(),
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            sliderValue?.let { value ->
                                scope.launch {
                                    downloadSettingsManager.setMaxConcurrentDownloads(value.roundToInt())
                                }
                            }
                        },
                        valueRange = DownloadSettingsManager.MIN_CONCURRENT.toFloat()..DownloadSettingsManager.MAX_CONCURRENT.toFloat(),
                        steps = DownloadSettingsManager.MAX_CONCURRENT - DownloadSettingsManager.MIN_CONCURRENT - 1
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${DownloadSettingsManager.MIN_CONCURRENT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${DownloadSettingsManager.MAX_CONCURRENT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            // Audio quality
            SettingsSection(title = "音频质量")

            SettingsInfoItem(
                icon = Icons.Default.MusicNote,
                title = "下载质量",
                subtitle = "自动选择最高可用音质 (最高192K Hi-Res)"
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Info card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "提示",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• 缓存用于加速在线播放，自动管理\n• 下载的文件保存在音乐文件夹中\n• 自动选择最高可用音质，大会员可获得Hi-Res无损音质\n• 实际音质取决于视频源和账户等级\n• 并发线程数建议设为2-4，过高可能触发B站风控",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsInfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    actionText: String,
    onActionClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(
            onClick = onActionClick,
            colors = ButtonDefaults.textButtonColors()
        ) {
            Text(actionText)
        }
    }
}

private fun calculateFolderSize(folder: File): Long {
    var size = 0L
    if (folder.exists() && folder.isDirectory) {
        folder.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                calculateFolderSize(file)
            } else {
                file.length()
            }
        }
    }
    return size
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}
