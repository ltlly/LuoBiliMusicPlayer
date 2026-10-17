package com.bilimusicplayer.desktop

import com.bilimusicplayer.core.AppContainer
import com.bilimusicplayer.download.DownloadService
import com.bilimusicplayer.platform.*
import com.bilimusicplayer.player.PlayerEngine
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class DesktopAppContainer(
    override val storage: AppStorage,
) : AppContainer {

    override val appVersion: String = "1.5.4"
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val player: PlayerEngine = DesktopPlayerEngine(storage)
    override val downloads: DownloadService = DesktopDownloadService(storage, scope)
    override val library: LocalLibraryService = DesktopLocalLibraryService(storage)
    override val files: FilePicker = DesktopFilePicker()
    override val audio: AudioCapabilities = DesktopAudioCapabilities()
    override val cache: AppCache = DesktopAppCache()
    override val qualityUpgrade: QualityUpgradeService = DesktopQualityUpgradeService()
    override val foldInfo = MutableStateFlow<FoldInfo?>(null)
    override var pendingRoute: String? = null
}