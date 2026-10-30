package com.bilimusicplayer.android

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import com.bilimusicplayer.platform.QualityUpgradeService
import com.bilimusicplayer.platform.UpgradeProgress
import com.bilimusicplayer.service.download.QualityUpgradeManager
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AndroidQualityUpgradeService(
    private val context: Context,
    storage: AppStorage,
) : QualityUpgradeService {
    private val manager = QualityUpgradeManager(context)

    /** Registered by MainActivity so the service can request write access for existing media. */
    var writePermissionRequester: (suspend (IntentSender) -> Boolean)? = null

    override val isSupported: Boolean = true

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
    )

    override val state: StateFlow<UpgradeProgress> = manager.state
        .map { it.toProgress() }
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, UpgradeProgress())

    override suspend fun start(retryFailed: Boolean) {
        val uris = manager.prepareUris()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty() && uris.any { !hasWriteAccess(it) }) {
            val intentSender = MediaStore.createWriteRequest(context.contentResolver, uris).intentSender
            val granted = writePermissionRequester?.invoke(intentSender) == true
            if (!granted) {
                manager.permissionRequired()
                return
            }
        }
        manager.start(uris, retryFailed)
    }

    private fun hasWriteAccess(uri: Uri): Boolean =
        context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

    override suspend fun pause() { manager.pause() }

    override suspend fun errors(): List<Pair<String, String>> = manager.errors()

    private fun com.bilimusicplayer.service.download.QualityUpgradeState.toProgress() = UpgradeProgress(
        status = status,
        done = done,
        total = total,
        upgraded = upgraded,
        unchanged = unchanged,
        failed = failed,
        current = current,
        error = error,
    )
}