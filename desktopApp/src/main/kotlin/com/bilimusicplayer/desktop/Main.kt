package com.bilimusicplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.bilimusicplayer.core.initSharedLogging
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.settings.AppStorage
import com.bilimusicplayer.settings.createSettings
import com.bilimusicplayer.shared.App
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.io.File

fun main() {
    initSharedLogging()
    val storage = AppStorage(createSettings())
    RetrofitClient.init(storage)
    runBlocking { RetrofitClient.ensureBuvidCookies() }

    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(System.getProperty("user.home"), ".cache/bilimusicplayer/images").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    val container = DesktopAppContainer(storage)
    val isMac = System.getProperty("os.name").lowercase().contains("mac")
    application {
        val closeWindow: () -> Unit = {
            runCatching { container.player.saveState() }
            exitApplication()
        }
        Window(
            onCloseRequest = closeWindow,
            title = "BiliMusicPlayer",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            LaunchedEffect(Unit) {
                if (isMac) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    window.isResizable = true
                }
                // Gradle-launched JVMs often start behind the previous front app.
                window.isVisible = true
                window.isAlwaysOnTop = true
                window.toFront()
                window.requestFocus()
                kotlinx.coroutines.delay(600)
                window.isAlwaysOnTop = false
                if (System.getenv("BILI_CAPTURE") != null) {
                    kotlinx.coroutines.delay(4000)
                    val image = java.awt.image.BufferedImage(
                        window.width,
                        window.height,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB,
                    )
                    val graphics = image.createGraphics()
                    window.paint(graphics)
                    graphics.dispose()
                    javax.imageio.ImageIO.write(image, "png", java.io.File("/tmp/bili-window.png"))
                }
            }
            Box(Modifier.fillMaxSize()) {
                App(
                    container = container,
                    windowChrome = if (isMac) {
                        { MacTitleBar(window) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}