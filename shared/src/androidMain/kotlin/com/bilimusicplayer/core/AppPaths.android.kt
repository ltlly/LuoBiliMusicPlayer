package com.bilimusicplayer.core

import android.os.Environment
import java.io.File

actual object AppPaths {
    actual fun databaseFile(): String =
        SharedContext.context.getDatabasePath("bili_music_database").absolutePath

    actual fun dataDir(): String = SharedContext.context.filesDir.absolutePath

    actual fun cacheDir(): String = SharedContext.context.cacheDir.absolutePath

    actual fun defaultDownloadDir(): String =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "BiliMusic",
        ).absolutePath
}