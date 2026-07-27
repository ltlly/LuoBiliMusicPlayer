package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.auth.BiliAuthRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val authRepository = remember {
        BiliAuthRepository(
            context = context,
            api = RetrofitClient.biliAuthApi,
            cookieJar = RetrofitClient.getCookieJar(),
            biliApi = RetrofitClient.biliApi
        )
    }

    var username by remember { mutableStateOf<String?>(null) }
    var userId by remember { mutableStateOf<Long?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Load user info
    LaunchedEffect(Unit) {
        scope.launch {
            username = authRepository.getUsername()
            userId = authRepository.getUserId()
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确定要退出登录吗？退出后需要重新扫码登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            authRepository.clearAuth()
                            showLogoutDialog = false
                            // Navigate back to login
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                ) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // About dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于") },
            text = {
                Column {
                    Text("B站音乐播放器", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("版本: 1.3.0", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("一款与哔哩哔哩收藏夹同步的音乐播放器", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("技术栈:", style = MaterialTheme.typography.bodySmall)
                    Text("• Kotlin + Jetpack Compose", style = MaterialTheme.typography.bodySmall)
                    Text("• Media3 ExoPlayer", style = MaterialTheme.typography.bodySmall)
                    Text("• Room Database", style = MaterialTheme.typography.bodySmall)
                    Text("• Material 3 Design", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Account section
            SettingsSectionLabel("账户")
            SettingsGroup {
                SettingsItem(
                    icon = Icons.Default.Person,
                    title = "账户",
                    subtitle = username?.let { "已登录: $it (UID: $userId)" } ?: "未登录",
                    showChevron = false
                ) {
                    // Show account info or login prompt
                }
                SettingsItem(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    title = "退出登录",
                    subtitle = "退出当前账户",
                    isDestructive = true
                ) {
                    showLogoutDialog = true
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // General section
            SettingsSectionLabel("通用")
            SettingsGroup {
                SettingsItem(
                    icon = Icons.Default.Download,
                    title = "下载设置",
                    subtitle = "音频质量和存储位置"
                ) {
                    navController.navigate("download_settings")
                }
                SettingsItem(
                    icon = Icons.Default.Palette,
                    title = "外观",
                    subtitle = "主题和显示选项"
                ) {
                    navController.navigate("appearance_settings")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // About section
            SettingsSectionLabel("关于")
            SettingsGroup {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "关于",
                    subtitle = "版本 1.3.0"
                ) {
                    showAboutDialog = true
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
    )
}

/**
 * Rounded container grouping related settings rows,
 * Apple Settings / Apple Music style.
 */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon in a rounded tile
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isDestructive) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isDestructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showChevron) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
