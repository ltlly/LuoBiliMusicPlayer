package com.bilimusicplayer.settings

import com.russhwolf.settings.Settings

/** Creates the persistent key-value store used by the shared core. */
expect fun createSettings(): Settings
