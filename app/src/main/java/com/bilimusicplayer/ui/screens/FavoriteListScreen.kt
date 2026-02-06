package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.auth.BiliAuthRepository
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.network.bilibili.favorite.FavoriteFolder
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoriteListScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val repository = remember {
        BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)
    }

    val authRepository = remember {
        BiliAuthRepository(
            context = context,
            api = RetrofitClient.biliAuthApi,
            cookieJar = RetrofitClient.getCookieJar(),
            biliApi = RetrofitClient.biliApi
        )
    }

    var favoriteFolders by remember { mutableStateOf<List<FavoriteFolder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                // Initialize WBI keys
                repository.initWbiKeys()

                // Get user ID from auth repository
                val userId = authRepository.getUserId()

                if (userId != null && userId != 0L) {
                    val response = repository.getFavoriteFolders(userId)
                    if (response.isSuccessful && response.body()?.code == 0) {
                        favoriteFolders = response.body()?.data?.list ?: emptyList()
                    } else {
                        errorMessage = "加载收藏夹失败: ${response.body()?.message ?: "未知错误"}"
                    }
                } else {
                    errorMessage = "请先登录"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "未知错误"
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("哔哩哔哩收藏夹") }
            )
        }
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
                            // Retry logic
                        }) {
                            Text("重试")
                        }
                    }
                }

                favoriteFolders.isEmpty() -> {
                    Text(
                        text = "未找到收藏夹",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(favoriteFolders) { folder ->
                            FavoriteFolderItem(folder = folder, onClick = {
                                // Navigate to folder contents
                                navController.navigate("favorite_content/${folder.id}/${folder.title}")
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FavoriteFolderItem(
    folder: FavoriteFolder,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${folder.mediaCount} 个视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
