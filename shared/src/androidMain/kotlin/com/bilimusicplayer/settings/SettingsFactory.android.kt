package com.bilimusicplayer.settings

import android.content.Context
import com.bilimusicplayer.core.SharedContext
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

actual fun createSettings(): Settings = SharedPreferencesSettings(
    SharedContext.context.getSharedPreferences("bili_settings", Context.MODE_PRIVATE),
)
