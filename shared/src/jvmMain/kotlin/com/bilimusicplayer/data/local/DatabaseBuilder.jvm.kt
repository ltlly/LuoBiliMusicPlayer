package com.bilimusicplayer.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import com.bilimusicplayer.core.AppPaths

actual fun createAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder<AppDatabase>(name = AppPaths.databaseFile())
