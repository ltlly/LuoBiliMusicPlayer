package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.clickable
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
import com.bilimusicplayer.ui.theme.ThemeManager
import com.bilimusicplayer.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeManager = remember { ThemeManager(context) }
    val currentThemeMode by themeManager.themeMode.collectAsState(initial = ThemeMode.SYSTEM)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("外观设置") },
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
            // Theme settings
            SettingsSection(title = "主题")

            ThemeOptionItem(
                icon = Icons.Default.Brightness6,
                title = "跟随系统",
                isSelected = currentThemeMode == ThemeMode.SYSTEM,
                onClick = {
                    scope.launch {
                        themeManager.setThemeMode(ThemeMode.SYSTEM)
                    }
                }
            )

            HorizontalDivider()

            ThemeOptionItem(
                icon = Icons.Default.LightMode,
                title = "浅色模式",
                isSelected = currentThemeMode == ThemeMode.LIGHT,
                onClick = {
                    scope.launch {
                        themeManager.setThemeMode(ThemeMode.LIGHT)
                    }
                }
            )

            HorizontalDivider()

            ThemeOptionItem(
                icon = Icons.Default.DarkMode,
                title = "深色模式",
                isSelected = currentThemeMode == ThemeMode.DARK,
                onClick = {
                    scope.launch {
                        themeManager.setThemeMode(ThemeMode.DARK)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

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
                        "• 跟随系统：根据系统深色/浅色模式自动切换\n• 浅色模式：始终使用浅色主题\n• 深色模式：始终使用深色主题\n\n应用使用 Material 3 设计系统，主题切换立即生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun ThemeOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
