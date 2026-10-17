package com.bilimusicplayer.android

import android.content.Context
import com.bilimusicplayer.BiliMusicApplication
import com.bilimusicplayer.core.AppContainer
import com.bilimusicplayer.download.DownloadService
import com.bilimusicplayer.platform.*
import com.bilimusicplayer.player.PlayerEngine
import com.bilimusicplayer.service.download.DownloadManager
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidAppContainer(
    private val app: BiliMusicApplication,
    override val storage: AppStorage,
    override val scope: CoroutineScope,
) : AppContainer {

    override val appVersion: String
        get() = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""

    override val player: PlayerEngine = Media3PlayerEngine(app, storage)

    override val downloads: DownloadService = AndroidDownloadService(
        DownloadManager(app, storage),
    )

    override val library: LocalLibraryService = AndroidLocalLibraryService(app, storage)

    override val files: FilePicker = AndroidFilePicker(app)

    override val audio: AudioCapabilities = AndroidAudioCapabilities(app)

    override val cache: AppCache = AndroidAppCache(app)

    override val qualityUpgrade: QualityUpgradeService = AndroidQualityUpgradeService(app, storage)

    override val foldInfo = MutableStateFlow<FoldInfo?>(null)

    override var pendingRoute: String? = null
}
