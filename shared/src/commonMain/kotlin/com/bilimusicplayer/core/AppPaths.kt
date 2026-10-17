package com.bilimusicplayer.core

/** Platform-specific application directories. */
expect object AppPaths {
    fun databaseFile(): String
    fun dataDir(): String
    fun cacheDir(): String
    fun defaultDownloadDir(): String
}
