package com.bilimusicplayer.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import com.bilimusicplayer.core.AppPaths
import com.bilimusicplayer.core.SharedContext

actual fun createAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder<AppDatabase>(SharedContext.context, AppPaths.databaseFile())
