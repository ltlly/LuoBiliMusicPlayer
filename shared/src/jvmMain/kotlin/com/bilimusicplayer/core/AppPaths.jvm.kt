package com.bilimusicplayer.core

import java.io.File

actual object AppPaths {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    private val home = System.getProperty("user.home")

    private val appDir: File = when {
        osName.contains("mac") -> File(home, "Library/Application Support/BiliMusicPlayer")
        osName.contains("win") -> File(System.getenv("APPDATA") ?: home, "BiliMusicPlayer")
        else -> File(home, ".local/share/BiliMusicPlayer")
    }

    actual fun databaseFile(): String = File(appDir, "bili_music_database").absolutePath

    actual fun dataDir(): String = appDir.absolutePath

    actual fun cacheDir(): String = File(appDir, "cache").absolutePath

    actual fun defaultDownloadDir(): String = File(home, "Music/BiliMusicPlayer").absolutePath
}
