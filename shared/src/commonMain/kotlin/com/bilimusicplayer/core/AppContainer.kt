package com.bilimusicplayer.core

import androidx.compose.runtime.staticCompositionLocalOf
import com.bilimusicplayer.download.DownloadService
import com.bilimusicplayer.platform.AppCache
import com.bilimusicplayer.platform.AudioCapabilities
import com.bilimusicplayer.platform.FilePicker
import com.bilimusicplayer.platform.FoldInfo
import com.bilimusicplayer.platform.LocalLibraryService
import com.bilimusicplayer.platform.QualityUpgradeService
import com.bilimusicplayer.player.PlayerEngine
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Everything the shared UI needs from the host platform. */
interface AppContainer {
    val appVersion: String
    val scope: CoroutineScope
    val storage: AppStorage
    val player: PlayerEngine
    val downloads: DownloadService
    val library: LocalLibraryService
    val files: FilePicker
    val cache: AppCache
    val qualityUpgrade: QualityUpgradeService
    val audio: AudioCapabilities
    val foldInfo: StateFlow<FoldInfo?>
    var pendingRoute: String?
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer has not been provided")
}