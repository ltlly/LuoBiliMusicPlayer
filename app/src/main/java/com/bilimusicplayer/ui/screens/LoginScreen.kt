package com.bilimusicplayer.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.auth.BiliAuthRepository
import com.bilimusicplayer.network.bilibili.auth.QRCodeLoginManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Bitmap
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
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

    val loginManager = remember { QRCodeLoginManager(authRepository) }
    val qrCodeUrl by loginManager.qrCodeUrl.collectAsState()
    val loginState by loginManager.loginState.collectAsState()

    // Check login state
    LaunchedEffect(loginState) {
        if (loginState is QRCodeLoginManager.LoginState.Success) {
            onLoginSuccess()
        }
    }

    // Start QR login on first composition
    LaunchedEffect(Unit) {
        scope.launch {
            val isLoggedIn = authRepository.isLoggedIn()
            if (isLoggedIn) {
                onLoginSuccess()
            } else {
                loginManager.startQRLogin()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            loginManager.cleanup()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录哔哩哔哩") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
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
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    qrCodeUrl?.let { url ->
                        val qrBitmap = remember(url) { generateQRCode(url, 512) }
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "二维码",
                                modifier = Modifier.size(300.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "打开哔哩哔哩App扫描此二维码以登录",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is QRCodeLoginManager.LoginState.Success -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("登录成功！")
                }

                is QRCodeLoginManager.LoginState.Error -> {
                    val errorMessage = (loginState as QRCodeLoginManager.LoginState.Error).message
                    Text(
                        text = "错误: $errorMessage",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            loginManager.startQRLogin()
                        }
                    }) {
                        Text("重试")
                    }
                }

                is QRCodeLoginManager.LoginState.Expired -> {
                    Text("二维码已过期")
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        scope.launch {
                            loginManager.startQRLogin()
                        }
                    }) {
                        Text("生成新二维码")
                    }
                }

                else -> {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Generate QR code bitmap from URL
 */
private fun generateQRCode(url: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(
                    x,
                    y,
                    if (bitMatrix.get(x, y)) android.graphics.Color.BLACK
                    else android.graphics.Color.WHITE
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
