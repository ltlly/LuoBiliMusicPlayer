package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ChevronRight
import com.bilimusicplayer.ui.components.BiliCoverLarge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.BiliFavoriteFolder
import com.bilimusicplayer.data.repository.FavoriteFolderCacheRepository
import com.bilimusicplayer.data.repository.toNetworkModel
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.auth.BiliAuthRepository
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.network.bilibili.favorite.FavoriteFolder
import kotlinx.coroutines.launch
import android.net.Uri

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

    val database = remember {
        AppDatabase.getDatabase(context)
    }

    val cacheRepository = remember {
        FavoriteFolderCacheRepository(database, repository)
    }

    var favoriteFolders by remember { mutableStateOf<List<BiliFavoriteFolder>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Load folders with cache support
    fun loadFolders(forceRefresh: Boolean = false) {
        scope.launch {
            if (forceRefresh) {
                isRefreshing = true
            } else {
                isLoading = true
            }
            errorMessage = null

            try {
                // Get user ID from auth repository
                val userId = authRepository.getUserId()

                if (userId != null && userId != 0L) {
                    // Load with cache support
                    val result = cacheRepository.getFavoriteFolders(userId, forceRefresh)
                    result.onSuccess { folders ->
                        favoriteFolders = folders
                    }.onFailure { e ->
                        errorMessage = e.message ?: "加载失败"
                    }
                } else {
                    errorMessage = "请先登录"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "未知错误"
            } finally {
                isLoading = false
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadFolders()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("哔哩哔哩收藏夹") },
                actions = {
                    IconButton(
                        onClick = { loadFolders(forceRefresh = true) },
                        enabled = !isRefreshing
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                }
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
                            loadFolders(forceRefresh = true)
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
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(260.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(favoriteFolders, key = { it.id }) { folder ->
                            FavoriteFolderItem(folder = folder.toNetworkModel(), onClick = {
                                // Navigate to folder contents
                                navController.navigate("favorite_content/${folder.id}/${Uri.encode(folder.title)}")
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
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        BiliCoverLarge(
            url = folder.cover,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            contentDescription = null
        )
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(folder.title, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("${folder.mediaCount} 首音频", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
