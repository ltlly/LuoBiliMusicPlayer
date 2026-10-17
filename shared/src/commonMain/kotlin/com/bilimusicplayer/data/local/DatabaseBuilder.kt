package com.bilimusicplayer.data.local

import androidx.room.RoomDatabase

/** Room needs a Context on Android; the desktop target only needs a path. */
expect fun createAppDatabaseBuilder(): RoomDatabase.Builder<AppDatabase>
