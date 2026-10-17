package com.bilimusicplayer.android

import android.content.Context
import com.bilimusicplayer.platform.FilePicker

class AndroidFilePicker(private val context: Context) : FilePicker {
    override fun pickDirectory(): String? = null
}
