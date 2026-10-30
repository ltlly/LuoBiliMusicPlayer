package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bilimusicplayer.core.LocalAppContainer
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.auth.BiliAuthRepository
import com.bilimusicplayer.network.bilibili.auth.QRCodeLoginManager
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.solid
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val authRepository = remember {
        BiliAuthRepository(
            api = RetrofitClient.biliAuthApi,
            cookieJar = RetrofitClient.getCookieJar(),
            biliApi = RetrofitClient.biliApi,
            storage = container.storage,
        )
    }

    val loginManager = remember { QRCodeLoginManager(authRepository) }
    val qrCodeUrl by loginManager.qrCodeUrl.collectAsState()
    val loginState by loginManager.loginState.collectAsState()

    LaunchedEffect(loginState) {
        if (loginState is QRCodeLoginManager.LoginState.Success) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(Unit) {
        if (authRepository.isLoggedIn()) {
            onLoginSuccess()
        } else {
            loginManager.startQRLogin()
        }
    }

    DisposableEffect(Unit) {
        onDispose { loginManager.cleanup() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("登录哔哩哔哩") }) },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (loginState) {
                is QRCodeLoginManager.LoginState.GeneratingQR -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("正在生成二维码...")
                }

                is QRCodeLoginManager.LoginState.WaitingForScan -> {
                    Text(
                        text = "使用哔哩哔哩App扫码",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    qrCodeUrl?.let { url ->
                        Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                            Image(
                                painter = rememberQrCodePainter(url) {
                                    colors {
                                        dark = QrBrush.solid(Color.Black)
                                        light = QrBrush.solid(Color.White)
                                        frame = QrBrush.solid(Color.Black)
                                        ball = QrBrush.solid(Color.Black)
                                    }
                                },
                                contentDescription = "二维码",
                                modifier = Modifier.padding(12.dp).size(300.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "打开哔哩哔哩App扫描此二维码以登录",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is QRCodeLoginManager.LoginState.Success -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("登录成功！")
                }

                is QRCodeLoginManager.LoginState.Error -> {
                    val errorMessage = (loginState as QRCodeLoginManager.LoginState.Error).message
                    Text(text = "错误: $errorMessage", color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { scope.launch { loginManager.startQRLogin() } }) {
                        Text("重试")
                    }
                }

                is QRCodeLoginManager.LoginState.Expired -> {
                    Text("二维码已过期")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { scope.launch { loginManager.startQRLogin() } }) {
                        Text("生成新二维码")
                    }
                }

                else -> CircularProgressIndicator()
            }
        }
    }
}