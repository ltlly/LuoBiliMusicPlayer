package com.bilimusicplayer.desktop

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
import okio.Path.Companion.toOkioPath
import java.io.File

fun main() {
    initSharedLogging()
    val storage = AppStorage(createSettings())
    RetrofitClient.init(storage)

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
    application {
        Window(
            onCloseRequest = {
                container.player.saveState()
                exitApplication()
            },
            title = "BiliMusicPlayer",
            state = rememberWindowState(size = DpSize(1100.dp, 760.dp)),
        ) {
            App(container)
        }
    }
}
