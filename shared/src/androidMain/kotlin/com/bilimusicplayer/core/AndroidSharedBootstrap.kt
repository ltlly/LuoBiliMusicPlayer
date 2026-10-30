package com.bilimusicplayer.core

import android.content.Context

/** Initializes the shared module from the Android Application. */
fun initShared(context: Context) {
    SharedContext.init(context)
    initSharedLogging()
}
