package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.bilimusicplayer.core.LocalAppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityUpgradeScreen(navController: NavController) {
    val container = LocalAppContainer.current
    val manager = container.qualityUpgrade
    val scope = rememberCoroutineScope()
    val downloadSettings by container.storage.downloadSettings.collectAsState()
    val progress by manager.state.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var retry by rememberSaveable { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    fun requestStart(retryFailed: Boolean = false) {
        retry = retryFailed
        scope.launch {
            busy = true
            error = ""
            try {
                manager.start(retryFailed)
            } catch (e: Exception) {
                error = e.message ?: "无法启动任务"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(progress.failed) { errors = manager.errors() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("升级已有歌曲音质") }, navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text("让收藏听得更好", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "使用当前登录账号，优先 Hi-Res 无损，其次杜比，再选最高普通 AAC。每首视频能获得的最高音质不同。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            val status = when {
                                !manager.isSupported -> "当前平台暂不支持音质升级"
                                progress.status == "running" -> "正在检查与升级"
                                progress.status == "paused" -> "已暂停"
                                progress.status == "permission" -> "需要继续授权"
                                progress.status == "finished" -> "本轮检查完成"
                                else -> "检查全部已下载歌曲"
                            }
                            Text(status, style = MaterialTheme.typography.titleLarge)
                            Text("${progress.done} / ${progress.total} 首")
                            LinearProgressIndicator(
                                progress = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text("已升级 ${progress.upgraded} · 已保留 ${progress.unchanged} · 未能升级 ${progress.failed}")
                            if (progress.current.isNotBlank()) Text(progress.current, style = MaterialTheme.typography.bodySmall)
                            if (progress.status == "running") {
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = { scope.launch { busy = true; try { manager.pause() } finally { busy = false } } },
                                ) { Text("暂停") }
                            } else {
                                Button(enabled = !busy && manager.isSupported, onClick = { requestStart() }) {
                                    Text(
                                        when {
                                            busy -> "准备中…"
                                            progress.status in listOf("paused", "permission") -> "继续"
                                            else -> "检查并升级全部歌曲"
                                        },
                                    )
                                }
                                if (progress.failed > 0) {
                                    TextButton(enabled = !busy, onClick = { requestStart(true) }) { Text("只重试未能升级的歌曲") }
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        "新音轨完整下载并验证后才替换原文件。下载失败、视频失效或账号暂时无法获取更高音质时，原歌曲继续保留。暂停、退出页面或重启应用后，已完成歌曲无需再检查。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "任务逐首执行；" + if (downloadSettings.apiRateLimitEnabled) {
                            "全局请求上限为每分钟 ${downloadSettings.apiRateLimitPerMinute} 次。"
                        } else {
                            "固定请求间隔已关闭。"
                        } + "出现风控会冷却后自动重试，可在下载设置中调整。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    if (progress.error.isNotBlank()) Text(progress.error, style = MaterialTheme.typography.bodySmall)
                }
                items(errors) { (title, reason) ->
                    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(reason) })
                }
            }
        }
    }
}
