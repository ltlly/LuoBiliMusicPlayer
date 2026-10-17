package com.bilimusicplayer.core

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier

/** Must be called by every platform entry point before creating an AppContainer. */
fun initSharedLogging() {
    Napier.base(DebugAntilog())
}
