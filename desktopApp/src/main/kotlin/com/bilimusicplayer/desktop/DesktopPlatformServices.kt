package com.bilimusicplayer.desktop

import com.bilimusicplayer.platform.AppCache
import com.bilimusicplayer.platform.AudioCapabilities
import com.bilimusicplayer.platform.FilePicker
import com.bilimusicplayer.platform.QualityUpgradeService
import com.bilimusicplayer.platform.UpgradeProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

class DesktopAudioCapabilities : AudioCapabilities {
    override val supportsDolby: Boolean = true
    override fun observeQualityLabel(path: String?): kotlinx.coroutines.flow.Flow<String?> = flowOf(null)
}

class DesktopFilePicker : FilePicker {
    override fun pickDirectory(): String? {
        val dialog = FileDialog(null as Frame?, "选择音乐文件夹", FileDialog.LOAD)
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val file = dialog.file
        return File(dir, file ?: "").absolutePath
    }
}

class DesktopAppCache : AppCache {
    override suspend fun sizeBytes(): Long = 0L
    override suspend fun clear() = Unit
}

class DesktopQualityUpgradeService : QualityUpgradeService {
    override val isSupported: Boolean = false
    override val state: StateFlow<UpgradeProgress> = MutableStateFlow(UpgradeProgress(status = "unsupported"))
    override suspend fun start(retryFailed: Boolean) = Unit
    override suspend fun pause() = Unit
    override suspend fun errors(): List<Pair<String, String>> = emptyList()
}