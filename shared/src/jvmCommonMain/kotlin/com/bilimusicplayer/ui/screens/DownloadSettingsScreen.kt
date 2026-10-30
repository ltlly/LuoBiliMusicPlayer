package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bilimusicplayer.core.LocalAppContainer
import com.bilimusicplayer.settings.DownloadSettings
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSettingsScreen(navController: NavController) {
    val container = LocalAppContainer.current
    val storage = container.storage
    val scope = rememberCoroutineScope()

    val settings by storage.downloadSettings.collectAsState()
    val wifiOnly = settings.wifiOnly
    // Calculate cache size
    var cacheSize by remember { mutableStateOf("计算中...") }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    // Concurrent downloads setting
    val maxConcurrentDownloads = settings.maxConcurrent
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    val displayValue = sliderValue?.roundToInt() ?: maxConcurrentDownloads

    // API rate limit setting
    val apiRateLimitEnabled = settings.apiRateLimitEnabled
    val apiRateLimit = settings.apiRateLimitPerMinute
    var rateLimitSliderValue by remember { mutableStateOf<Float?>(null) }
    val rateLimitDisplayValue = rateLimitSliderValue?.roundToInt() ?: apiRateLimit

    LaunchedEffect(Unit) {
        cacheSize = formatFileSize(container.cache.sizeBytes())
    }

    // Clear cache dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("清除缓存") },
            text = { Text("确定要清除所有缓存吗？这将清除播放和封面缓存，已下载歌曲、登录信息和音质升级记录会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            container.cache.clear()
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
                .verticalScroll(rememberScrollState())
        ) {
            if (container.qualityUpgrade.isSupported) {
                SettingsActionItem(Icons.Default.HighQuality, "升级已有歌曲", "逐首检查并升级，失败保留原文件", "查看") { navController.navigate("quality_upgrade") }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("仅在 Wi-Fi 等非计费网络下载", style = MaterialTheme.typography.titleMedium)
                    Text("新任务生效；升级任务暂停后继续可应用新设置", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = wifiOnly, onCheckedChange = { checked ->
                    storage.updateDownloadSettings { it.copy(wifiOnly = checked) }
                })
            }
            // Download path
            SettingsSection(title = "存储位置")

            SettingsInfoItem(
                icon = Icons.Default.Folder,
                title = "下载路径",
                subtitle = settings.downloadDir
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
                            text = "同时下载数量",
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
                                    storage.updateDownloadSettings { it.copy(maxConcurrent = value.roundToInt()) }
                                }
                            }
                        },
                        valueRange = DownloadSettings.MIN_CONCURRENT.toFloat()..DownloadSettings.MAX_CONCURRENT.toFloat(),
                        steps = DownloadSettings.MAX_CONCURRENT - DownloadSettings.MIN_CONCURRENT - 1
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${DownloadSettings.MIN_CONCURRENT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${DownloadSettings.MAX_CONCURRENT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider()

            // API rate limit setting
            SettingsSection(title = "API限速")

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("限制请求频率", style = MaterialTheme.typography.titleMedium)
                    Text(if (apiRateLimitEnabled) "按下方频率处理请求" else "连续处理请求；遇到风控自动冷却", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = apiRateLimitEnabled, onCheckedChange = { checked ->
                    storage.updateDownloadSettings { it.copy(apiRateLimitEnabled = checked) }
                })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
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
                            text = "每分钟最大请求数",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (apiRateLimitEnabled) "$rateLimitDisplayValue 次/分" else "限速已关闭",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "控制播放列表加载和批量下载的API请求速率，降低可减少被B站风控的风险",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = rateLimitSliderValue ?: apiRateLimit.toFloat(),
                        enabled = apiRateLimitEnabled,
                        onValueChange = { rateLimitSliderValue = it },
                        onValueChangeFinished = {
                            rateLimitSliderValue?.let { value ->
                                scope.launch {
                                    storage.updateDownloadSettings { it.copy(apiRateLimitPerMinute = value.roundToInt()) }
                                }
                            }
                        },
                        valueRange = DownloadSettings.MIN_API_RATE_LIMIT.toFloat()..DownloadSettings.MAX_API_RATE_LIMIT.toFloat(),
                        steps = (DownloadSettings.MAX_API_RATE_LIMIT - DownloadSettings.MIN_API_RATE_LIMIT) / 5 - 1
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${DownloadSettings.MIN_API_RATE_LIMIT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${DownloadSettings.MAX_API_RATE_LIMIT}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SettingsActionItem(
                icon = Icons.Default.Timer,
                title = "解除请求冷却",
                subtitle = "清除本地冷却计时并立即重试；不会绕过 B站 服务端限制",
                actionText = "解除",
                onActionClick = { storage.clearRequestCooldown() }
            )

            HorizontalDivider()

            // Audio quality
            SettingsSection(title = "音频质量")

            SettingsInfoItem(
                icon = Icons.Default.MusicNote,
                title = "下载质量",
                subtitle = "默认最高可用：Hi-Res 无损 → 杜比 → AAC"
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
                        "• 缓存用于加速在线播放，自动管理\n• 下载的文件保存在音乐文件夹中\n• 开始下载时使用当前登录账号重新获取音轨\n• 优先 Hi-Res 无损，其次杜比，最后最高普通 AAC\n• 保存原始音轨，不转 MP3；192 kbps AAC 不等于 Hi-Res\n• 已有歌曲可通过音质升级逐首检查\n• 实际音质取决于视频源和账户等级\n• API请求限速默认关闭，可随时启用\n• API请求依次处理；遇到风控自动冷却",
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

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.2f KB", size / 1024.0)
        size < 1024 * 1024 * 1024 -> String.format("%.2f MB", size / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0))
    }
}