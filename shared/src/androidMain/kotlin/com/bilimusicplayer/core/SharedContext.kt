package com.bilimusicplayer.core

import android.content.Context

/** Set once from the Android Application before any shared service is used. */
object SharedContext {
    lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}
